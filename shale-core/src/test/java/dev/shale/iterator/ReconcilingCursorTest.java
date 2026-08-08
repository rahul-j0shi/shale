package dev.shale.iterator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.shale.BytewiseComparator;
import dev.shale.Cursor;
import dev.shale.internal.key.InternalKey;
import dev.shale.internal.key.InternalKeyComparator;
import dev.shale.internal.key.ValueType;
import dev.shale.memtable.Memtable;
import dev.shale.memtable.TreeMemtable;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Reconciliation: the three rules that turn a merged internal-key stream into what a reader sees
 * (ADR-0011) — the newest version of a user key wins, a tombstone hides the key entirely, and the
 * scan stops at its upper bound.
 *
 * <p>Sources are ordered newest-first here, as the engine orders them, but note that the cursor
 * never consults that order: precedence rides in the sequence number, so a version that is newer
 * decides the answer even if it sits in an older source. Two cases below deliberately put the
 * winning version in the "wrong" source to hold that property down.
 */
class ReconcilingCursorTest {

  private static final InternalKeyComparator ORDERING =
      new InternalKeyComparator(BytewiseComparator.INSTANCE);

  @Test
  void emptySources_yieldNothing() {
    try (Cursor cursor = scan(null, null, source())) {
      assertThat(cursor.isValid()).isFalse();
    }
  }

  @Test
  void singleSource_yieldsLiveKeysInOrder() {
    try (Cursor cursor = scan(null, null, source("a#1=x", "b#2=y"))) {
      assertThat(drain(cursor)).containsExactly("a=x", "b=y");
    }
  }

  @Test
  void newestVersionOfAUserKeyWins() {
    try (Cursor cursor = scan(null, null, source("k#9=new"), source("k#2=old"))) {
      assertThat(drain(cursor)).containsExactly("k=new");
    }
  }

  @Test
  void newestVersionWins_evenWhenItSitsInTheOlderSource() {
    // Precedence is the sequence number, not the source's position. If the cursor were picking
    // "first source that has the key", this would answer "stale".
    try (Cursor cursor = scan(null, null, source("k#2=stale"), source("k#9=fresh"))) {
      assertThat(drain(cursor)).containsExactly("k=fresh");
    }
  }

  @Test
  void tombstoneHidesOlderValue() {
    Memtable deleted = new TreeMemtable(BytewiseComparator.INSTANCE);
    deleted.add(new InternalKey(bytes("k"), 9, ValueType.DELETE).encode(), new byte[0]);

    try (Cursor cursor = scan(null, null, deleted, source("k#2=old"))) {
      assertThat(cursor.isValid()).isFalse();
    }
  }

  @Test
  void tombstoneDoesNotHideANewerValue() {
    Memtable deleted = new TreeMemtable(BytewiseComparator.INSTANCE);
    deleted.add(new InternalKey(bytes("k"), 3, ValueType.DELETE).encode(), new byte[0]);

    // Delete at 3, then a re-put at 7: the key is live again.
    try (Cursor cursor = scan(null, null, deleted, source("k#7=back"))) {
      assertThat(drain(cursor)).containsExactly("k=back");
    }
  }

  @Test
  void tombstoneHidesOnlyItsOwnKey() {
    Memtable deleted = new TreeMemtable(BytewiseComparator.INSTANCE);
    deleted.add(new InternalKey(bytes("b"), 9, ValueType.DELETE).encode(), new byte[0]);

    try (Cursor cursor = scan(null, null, deleted, source("a#1=x", "b#2=y", "c#3=z"))) {
      assertThat(drain(cursor)).containsExactly("a=x", "c=z");
    }
  }

  @Test
  void consecutiveTombstonesAreAllSkipped() {
    Memtable deleted = new TreeMemtable(BytewiseComparator.INSTANCE);
    for (String userKey : List.of("b", "c", "d")) {
      deleted.add(new InternalKey(bytes(userKey), 9, ValueType.DELETE).encode(), new byte[0]);
    }

    // Three hidden keys in a row must not stall the advance or leak a deleted key.
    try (Cursor cursor =
        scan(null, null, deleted, source("a#1=x", "b#2=y", "c#2=y", "d#2=y", "e#3=z"))) {
      assertThat(drain(cursor)).containsExactly("a=x", "e=z");
    }
  }

  @Test
  void lowerBoundIsInclusive() {
    try (Cursor cursor = scan(bytes("b"), null, source("a#1=x", "b#1=y", "c#1=z"))) {
      assertThat(drain(cursor)).containsExactly("b=y", "c=z");
    }
  }

  @Test
  void upperBoundIsExclusive() {
    try (Cursor cursor = scan(null, bytes("c"), source("a#1=x", "b#1=y", "c#1=z"))) {
      assertThat(drain(cursor)).containsExactly("a=x", "b=y");
    }
  }

  @Test
  void boundsTogetherSelectAWindow() {
    try (Cursor cursor = scan(bytes("b"), bytes("d"), source("a#1=w", "b#1=x", "c#1=y", "d#1=z"))) {
      assertThat(drain(cursor)).containsExactly("b=x", "c=y");
    }
  }

  @Test
  void emptyRange_yieldsNothing() {
    try (Cursor cursor = scan(bytes("b"), bytes("b"), source("a#1=x", "b#1=y"))) {
      assertThat(cursor.isValid()).isFalse();
    }
  }

  @Test
  void tombstoneAtTheLowerBound_doesNotLeak() {
    Memtable deleted = new TreeMemtable(BytewiseComparator.INSTANCE);
    deleted.add(new InternalKey(bytes("b"), 9, ValueType.DELETE).encode(), new byte[0]);

    try (Cursor cursor = scan(bytes("b"), null, deleted, source("b#1=y", "c#1=z"))) {
      assertThat(drain(cursor)).containsExactly("c=z");
    }
  }

  @Test
  void returnedBytesSurviveAdvancing() {
    // Cursor hands its bytes to a caller, who may keep them — the materialising cursor this
    // replaced returned copies, and streaming must not quietly downgrade that guarantee.
    try (Cursor cursor = scan(null, null, source("a#1=first", "b#1=second"))) {
      byte[] firstKey = cursor.key();
      byte[] firstValue = cursor.value();
      cursor.next();

      assertThat(firstKey).isEqualTo(bytes("a"));
      assertThat(firstValue).isEqualTo(bytes("first"));
    }
  }

  @Test
  void mutatingTheReturnedValueDoesNotCorruptTheSource() {
    // The value is the array that matters here: an InternalIterator hands back the memtable node's
    // own value bytes, so without a copy a misbehaving caller would rewrite stored data in place.
    // (The key is copied by InternalKey.decode already; the cursor copies it too rather than
    // depending on another class's implementation detail for its own API guarantee.)
    Memtable memtable = source("a#1=xxx", "b#1=yyy");
    try (Cursor cursor = scan(null, null, memtable)) {
      cursor.value()[0] = 'z';
      cursor.key()[0] = 'z';
    }
    try (Cursor cursor = scan(null, null, memtable)) {
      assertThat(drain(cursor)).containsExactly("a=xxx", "b=yyy");
    }
  }

  @Test
  void closedCursor_isNotValid_andRefusesAccess() {
    Cursor cursor = scan(null, null, source("a#1=x"));
    assertThat(cursor.isValid()).isTrue();
    cursor.close();

    assertThat(cursor.isValid()).isFalse();
    assertThatThrownBy(cursor::key).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(cursor::value).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void close_isIdempotent() {
    Cursor cursor = scan(null, null, source("a#1=x"));
    cursor.close();
    cursor.close();
  }

  @Test
  void close_releasesEveryPinnedTableExactlyOnce() {
    // N6: the cursor takes one reference per table it can read from, and gives them all back.
    // Counting release calls is the only way to prove the pairing without a live SSTable.
    CountingTable first = new CountingTable();
    CountingTable second = new CountingTable();

    ReconcilingCursor cursor =
        new ReconcilingCursor(
            new MergingIterator(List.of(source("a#1=x").iterator()), ORDERING),
            BytewiseComparator.INSTANCE,
            null,
            null,
            List.of(first, second));
    // Constructing pinned them; nothing is released however long the caller holds the cursor.
    assertThat(first.retains).isEqualTo(1);
    assertThat(second.retains).isEqualTo(1);
    assertThat(first.releases).isZero();

    cursor.close();
    cursor.close(); // a second close must not double-release a table another reader still holds

    assertThat(first.releases).isEqualTo(1);
    assertThat(second.releases).isEqualTo(1);
  }

  @Test
  void narrowScan_doesNotWalkEntriesOutsideItsRange() {
    // The point of M4: a bounded scan seeks, it does not filter. Counting how far the cursor
    // advances its source proves that directly — the old materialising scan decoded all 200
    // entries whatever range was asked for, so this is the regression that matters most.
    Memtable memtable = new TreeMemtable(BytewiseComparator.INSTANCE);
    for (int i = 0; i < 200; i++) {
      memtable.add(new InternalKey(key(i), 1, ValueType.PUT).encode(), bytes(String.valueOf(i)));
    }
    CountingIterator counted = new CountingIterator(memtable.iterator());

    List<String> found = new ArrayList<>();
    try (Cursor cursor =
        new ReconcilingCursor(
            new MergingIterator(List.of(counted), ORDERING),
            BytewiseComparator.INSTANCE,
            key(100),
            key(103),
            List.of())) {
      for (; cursor.isValid(); cursor.next()) {
        found.add(new String(cursor.key(), StandardCharsets.US_ASCII));
      }
    }

    assertThat(found).containsExactly("key100", "key101", "key102");
    // One seek, then a step per entry returned plus the one that crossed the upper bound.
    assertThat(counted.seeks).isEqualTo(1);
    assertThat(counted.advances)
        .as("advances must scale with the result, not the source")
        .isLessThanOrEqualTo(found.size() + 1);
  }

  /** Delegates, counting how far it is driven — the evidence for "seek, not filter". */
  private static final class CountingIterator implements InternalIterator {
    private final InternalIterator delegate;
    private int seeks;
    private int advances;

    CountingIterator(InternalIterator delegate) {
      this.delegate = delegate;
    }

    @Override
    public void seek(byte[] internalKey) {
      seeks++;
      delegate.seek(internalKey);
    }

    @Override
    public void seekToFirst() {
      seeks++;
      delegate.seekToFirst();
    }

    @Override
    public boolean valid() {
      return delegate.valid();
    }

    @Override
    public void next() {
      advances++;
      delegate.next();
    }

    @Override
    public byte[] internalKey() {
      return delegate.internalKey();
    }

    @Override
    public byte[] value() {
      return delegate.value();
    }

    @Override
    public void close() {
      delegate.close();
    }
  }

  private static byte[] key(int i) {
    return String.format("key%03d", i).getBytes(StandardCharsets.US_ASCII);
  }

  /** Counts its reference operations, so the retain/release pairing can be asserted directly. */
  private static final class CountingTable implements ReferenceCounted {
    private int retains;
    private int releases;

    @Override
    public void retain() {
      retains++;
    }

    @Override
    public void release() {
      releases++;
    }
  }

  /** Builds a cursor over the given sources, newest first. */
  private static Cursor scan(byte[] fromInclusive, byte[] toExclusive, Memtable... sources) {
    List<InternalIterator> children = Arrays.stream(sources).map(Memtable::iterator).toList();
    return new ReconcilingCursor(
        new MergingIterator(children, ORDERING),
        BytewiseComparator.INSTANCE,
        fromInclusive,
        toExclusive,
        List.of());
  }

  /** Drains a cursor into {@code userKey=value} strings. */
  private static List<String> drain(Cursor cursor) {
    List<String> out = new ArrayList<>();
    for (; cursor.isValid(); cursor.next()) {
      out.add(
          new String(cursor.key(), StandardCharsets.US_ASCII)
              + "="
              + new String(cursor.value(), StandardCharsets.US_ASCII));
    }
    return out;
  }

  /** A memtable of {@code userKey#sequence=value} puts. */
  private static Memtable source(String... entries) {
    Memtable memtable = new TreeMemtable(BytewiseComparator.INSTANCE);
    for (String entry : entries) {
      String[] keyAndValue = entry.split("=");
      String[] parts = keyAndValue[0].split("#");
      memtable.add(
          new InternalKey(bytes(parts[0]), Long.parseLong(parts[1]), ValueType.PUT).encode(),
          bytes(keyAndValue[1]));
    }
    return memtable;
  }

  private static byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.US_ASCII);
  }
}
