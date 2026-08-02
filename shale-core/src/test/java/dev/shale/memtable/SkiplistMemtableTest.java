package dev.shale.memtable;

import static org.assertj.core.api.Assertions.assertThat;

import dev.shale.BytewiseComparator;
import dev.shale.internal.key.InternalKey;
import dev.shale.internal.key.ValueType;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Single-threaded behaviour of the hand-written skiplist (ADR-0009): it must order encoded internal
 * keys exactly as {@link dev.shale.internal.key.InternalKeyComparator} does — user key ascending,
 * sequence descending — and expose {@code add}/{@code ceiling}/{@code entries}/{@code sizeBytes}
 * with the same semantics as {@link TreeMemtable}. Concurrency is covered separately.
 */
class SkiplistMemtableTest {

  private final SkiplistMemtable memtable = new SkiplistMemtable(BytewiseComparator.INSTANCE, 1L);

  @Test
  void empty_hasNoEntriesNoCeilingZeroSize() {
    assertThat(memtable.entries()).isEmpty();
    assertThat(memtable.ceiling(put("a", 1))).isNull();
    assertThat(memtable.sizeBytes()).isZero();
  }

  @Test
  void add_thenCeilingFindsIt_andAccountsSize() {
    byte[] key = put("a", 1);
    memtable.add(key, value("v"));

    Memtable.Entry entry = memtable.ceiling(key);
    assertThat(entry).isNotNull();
    assertThat(entry.internalKey()).isEqualTo(key);
    assertThat(entry.value()).isEqualTo(value("v"));
    assertThat(memtable.sizeBytes()).isEqualTo((long) key.length + value("v").length);
  }

  @Test
  void entries_areAscendingByInternalKeyOrder_userAscSeqDesc() {
    // insert deliberately out of order
    memtable.add(put("b", 5), value("b5"));
    memtable.add(put("a", 2), value("a2"));
    memtable.add(put("a", 9), value("a9")); // newer version of "a" sorts before a@2
    memtable.add(put("c", 1), value("c1"));

    List<String> order = memtable.entries().stream().map(SkiplistMemtableTest::describe).toList();
    assertThat(order).containsExactly("a#9", "a#2", "b#5", "c#1");
  }

  @Test
  void ceiling_returnsSmallestEntryAtOrAfterKey() {
    memtable.add(put("a", 5), value("a"));
    memtable.add(put("c", 5), value("c"));

    // seek "b" at max sequence: no "b", next in order is "c"
    Memtable.Entry entry = memtable.ceiling(seek("b"));
    assertThat(describe(entry)).isEqualTo("c#5");
  }

  @Test
  void ceiling_pastLastKey_isNull() {
    memtable.add(put("a", 5), value("a"));
    assertThat(memtable.ceiling(seek("z"))).isNull();
  }

  @Test
  void seekAtMaxSequence_landsOnNewestVersion() {
    memtable.add(put("k", 1), value("old"));
    memtable.add(put("k", 7), value("new"));

    Memtable.Entry entry = memtable.ceiling(seek("k"));
    assertThat(entry.value()).isEqualTo(value("new"));
    assertThat(describe(entry)).isEqualTo("k#7");
  }

  @Test
  void tombstoneEntry_isStoredLikeAnyOther() {
    byte[] key = new InternalKey(bytes("d"), 3, ValueType.DELETE).encode();
    memtable.add(key, new byte[0]);

    Memtable.Entry entry = memtable.ceiling(seek("d"));
    assertThat(entry.internalKey()).isEqualTo(key);
    assertThat(entry.value()).isEmpty();
  }

  private static byte[] put(String userKey, long sequence) {
    return new InternalKey(bytes(userKey), sequence, ValueType.PUT).encode();
  }

  private static byte[] seek(String userKey) {
    return new InternalKey(bytes(userKey), InternalKey.MAX_SEQUENCE, ValueType.FOR_SEEK).encode();
  }

  private static String describe(Memtable.Entry entry) {
    InternalKey key = InternalKey.decode(entry.internalKey());
    return new String(key.userKey(), StandardCharsets.US_ASCII) + "#" + key.sequenceNumber();
  }

  private static byte[] value(String value) {
    return bytes(value);
  }

  private static byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.US_ASCII);
  }
}
