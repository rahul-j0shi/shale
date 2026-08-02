package dev.shale.memtable;

import static org.assertj.core.api.Assertions.assertThat;

import dev.shale.BytewiseComparator;
import dev.shale.internal.key.InternalKey;
import dev.shale.internal.key.ValueType;
import java.util.HexFormat;
import java.util.List;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;

/**
 * The skiplist must be observationally identical to a balanced tree. We drive the same random
 * operation sequence into {@link SkiplistMemtable} and a {@link TreeMemtable} oracle and assert
 * they agree on full iteration order and on {@code ceiling} for every probe. Any divergence in the
 * hand-written structure shows up here and shrinks to a minimal counterexample (ADR-0009).
 *
 * <p>Each operation's sequence number is its index, so every internal key is unique — matching the
 * engine, where {@code nextSequence++} guarantees uniqueness.
 */
class SkiplistMemtablePropertyTest {

  private static final int USER_KEY_SPACE = 16; // small, to force multiple versions and tombstones

  @Property
  void skiplistMatchesTreeMap_onEntriesAndCeiling(
      @ForAll @Size(max = 400) List<@IntRange(min = 0, max = 2 * USER_KEY_SPACE - 1) Integer> ops) {
    SkiplistMemtable skiplist = new SkiplistMemtable(BytewiseComparator.INSTANCE, 42L);
    TreeMemtable tree = new TreeMemtable(BytewiseComparator.INSTANCE);

    long sequence = 1;
    for (int op : ops) {
      int userKeyId = op % USER_KEY_SPACE;
      ValueType type = op < USER_KEY_SPACE ? ValueType.PUT : ValueType.DELETE;
      byte[] internalKey = new InternalKey(userKey(userKeyId), sequence, type).encode();
      byte[] value = type == ValueType.PUT ? value(userKeyId, sequence) : new byte[0];
      skiplist.add(internalKey, value);
      tree.add(internalKey, value);
      sequence++;
    }

    assertThat(describe(skiplist.entries())).isEqualTo(describe(tree.entries()));
    for (int id = 0; id < USER_KEY_SPACE; id++) {
      byte[] probe =
          new InternalKey(userKey(id), InternalKey.MAX_SEQUENCE, ValueType.FOR_SEEK).encode();
      assertThat(describe(skiplist.ceiling(probe))).isEqualTo(describe(tree.ceiling(probe)));
    }
    byte[] pastEnd = new InternalKey(new byte[] {(byte) 0xFF}, 0, ValueType.PUT).encode();
    assertThat(skiplist.ceiling(pastEnd)).isNull();
    assertThat(tree.ceiling(pastEnd)).isNull();
    assertThat(skiplist.sizeBytes()).isEqualTo(tree.sizeBytes());
  }

  private static byte[] userKey(int id) {
    return new byte[] {(byte) id};
  }

  private static byte[] value(int id, long sequence) {
    return new byte[] {(byte) id, (byte) sequence, (byte) (sequence >>> 8)};
  }

  private static List<String> describe(List<Memtable.Entry> entries) {
    return entries.stream().map(SkiplistMemtablePropertyTest::describe).toList();
  }

  private static String describe(Memtable.Entry entry) {
    if (entry == null) {
      return "null";
    }
    HexFormat hex = HexFormat.of();
    return hex.formatHex(entry.internalKey()) + ":" + hex.formatHex(entry.value());
  }
}
