package dev.shale.iterator;

import static org.assertj.core.api.Assertions.assertThat;

import dev.shale.BytewiseComparator;
import dev.shale.internal.key.InternalKey;
import dev.shale.internal.key.InternalKeyComparator;
import dev.shale.internal.key.ValueType;
import dev.shale.memtable.Memtable;
import dev.shale.memtable.TreeMemtable;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The heap merge (ADR-0011). It must yield the global internal-key order across any number of
 * children — user key ascending, sequence descending — and nothing else: it does not hide
 * tombstones, does not drop superseded versions, and does not care which source an entry came from.
 * That interpretation belongs to {@link ReconcilingCursor}, and keeping it out of here is what lets
 * compaction (M6) reuse this class unchanged while keeping entries this cursor would hide.
 *
 * <p>Children are {@link TreeMemtable}s because the merge is indifferent to what a child is, and an
 * in-memory child keeps these cases readable. Merging real SSTables is covered end to end by the
 * engine suites.
 */
class MergingIteratorTest {

  private static final InternalKeyComparator ORDERING =
      new InternalKeyComparator(BytewiseComparator.INSTANCE);

  @Test
  void noChildren_isNeverValid() {
    try (MergingIterator merged = new MergingIterator(List.of(), ORDERING)) {
      merged.seekToFirst();
      assertThat(merged.valid()).isFalse();
      merged.seek(seek("a"));
      assertThat(merged.valid()).isFalse();
    }
  }

  @Test
  void allChildrenEmpty_isNeverValid() {
    try (MergingIterator merged = merge(source(), source(), source())) {
      merged.seekToFirst();
      assertThat(merged.valid()).isFalse();
    }
  }

  @Test
  void singleChild_passesThrough() {
    try (MergingIterator merged = merge(source("a#3", "b#1"))) {
      assertThat(Drain.describeAll(merged)).containsExactly("a#3", "b#1");
    }
  }

  @Test
  void interleavesDisjointChildren() {
    try (MergingIterator merged =
        merge(source("a#1", "d#1"), source("b#1", "e#1"), source("c#1"))) {
      assertThat(Drain.describeAll(merged)).containsExactly("a#1", "b#1", "c#1", "d#1", "e#1");
    }
  }

  @Test
  void emptyChildAmongNonEmptyOnes_isIgnored() {
    try (MergingIterator merged = merge(source("a#1"), source(), source("b#1"))) {
      assertThat(Drain.describeAll(merged)).containsExactly("a#1", "b#1");
    }
  }

  @Test
  void sameUserKeyAcrossSources_emergesSequenceDescending() {
    // The rule the whole read path rests on: whichever source holds it, the newest version of a
    // user key sorts first, so reconciliation is "take the first, skip the rest".
    try (MergingIterator merged = merge(source("k#2"), source("k#9"), source("k#5"))) {
      assertThat(Drain.describeAll(merged)).containsExactly("k#9", "k#5", "k#2");
    }
  }

  @Test
  void doesNotHideTombstonesOrSupersededVersions() {
    Memtable withTombstone = new TreeMemtable(BytewiseComparator.INSTANCE);
    withTombstone.add(new InternalKey(bytes("k"), 7, ValueType.DELETE).encode(), new byte[0]);

    try (MergingIterator merged = merge(withTombstone, source("k#3"))) {
      // Both survive the merge; only the cursor above decides what a reader sees.
      assertThat(Drain.describeAll(merged)).containsExactly("k#7", "k#3");
    }
  }

  @Test
  void seek_positionsEveryChild() {
    try (MergingIterator merged = merge(source("a#1", "m#1"), source("b#1", "n#1"))) {
      assertThat(Drain.describeAll(merged)).containsExactly("a#1", "b#1", "m#1", "n#1");
      merged.seek(seek("m"));
      assertThat(Drain.describe(merged.internalKey())).isEqualTo("m#1");
      merged.next();
      assertThat(Drain.describe(merged.internalKey())).isEqualTo("n#1");
    }
  }

  @Test
  void seek_pastEveryChild_isInvalid() {
    try (MergingIterator merged = merge(source("a#1"), source("b#1"))) {
      merged.seek(seek("z"));
      assertThat(merged.valid()).isFalse();
    }
  }

  @Test
  void seek_backwards_repositions() {
    try (MergingIterator merged = merge(source("a#1", "m#1"), source("b#1"))) {
      merged.seek(seek("m"));
      merged.seek(seek("a"));
      assertThat(Drain.describe(merged.internalKey())).isEqualTo("a#1");
    }
  }

  @Test
  void exhaustingOneChildEarly_drainsTheOthers() {
    // One child runs out long before the rest; the heap must shrink rather than stall.
    try (MergingIterator merged = merge(source("a#1"), source("b#1", "c#1", "d#1"))) {
      assertThat(Drain.describeAll(merged)).containsExactly("a#1", "b#1", "c#1", "d#1");
    }
  }

  @Test
  void manyChildren_mergeInOrder() {
    // Enough children that the heap actually has depth, with keys deliberately interleaved.
    Memtable[] sources = new Memtable[8];
    for (int i = 0; i < sources.length; i++) {
      Memtable source = new TreeMemtable(BytewiseComparator.INSTANCE);
      for (int slot = i; slot < 64; slot += sources.length) {
        source.add(new InternalKey(key(slot), 1, ValueType.PUT).encode(), new byte[0]);
      }
      sources[i] = source;
    }

    try (MergingIterator merged = merge(sources)) {
      List<String> expected =
          java.util.stream.IntStream.range(0, 64)
              .mapToObj(slot -> new String(key(slot), StandardCharsets.US_ASCII) + "#1")
              .toList();
      assertThat(Drain.describeAll(merged)).isEqualTo(expected);
    }
  }

  @Test
  void close_closesEveryChild_andIsIdempotent() {
    CountingIterator first = new CountingIterator();
    CountingIterator second = new CountingIterator();
    MergingIterator merged = new MergingIterator(List.of(first, second), ORDERING);
    merged.close();
    merged.close();

    assertThat(first.closes).isPositive();
    assertThat(second.closes).isPositive();
  }

  /** Records that it was closed; the merge owns its children's lifetimes. */
  private static final class CountingIterator implements InternalIterator {
    private int closes;

    @Override
    public void seek(byte[] internalKey) {}

    @Override
    public void seekToFirst() {}

    @Override
    public boolean valid() {
      return false;
    }

    @Override
    public void next() {}

    @Override
    public byte[] internalKey() {
      throw new IllegalStateException("not valid");
    }

    @Override
    public byte[] value() {
      throw new IllegalStateException("not valid");
    }

    @Override
    public void close() {
      closes++;
    }
  }

  private static MergingIterator merge(Memtable... sources) {
    return new MergingIterator(
        java.util.Arrays.stream(sources).map(Memtable::iterator).toList(), ORDERING);
  }

  /** A memtable holding the given {@code userKey#sequence} entries, all puts. */
  private static Memtable source(String... entries) {
    Memtable memtable = new TreeMemtable(BytewiseComparator.INSTANCE);
    for (String entry : entries) {
      String[] parts = entry.split("#");
      memtable.add(
          new InternalKey(bytes(parts[0]), Long.parseLong(parts[1]), ValueType.PUT).encode(),
          bytes(entry));
    }
    return memtable;
  }

  private static byte[] key(int i) {
    return String.format("key%03d", i).getBytes(StandardCharsets.US_ASCII);
  }

  private static byte[] seek(String userKey) {
    return new InternalKey(bytes(userKey), InternalKey.MAX_SEQUENCE, ValueType.FOR_SEEK).encode();
  }

  private static byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.US_ASCII);
  }
}
