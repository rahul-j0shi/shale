package dev.shale.sstable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.shale.BytewiseComparator;
import dev.shale.CorruptionException;
import dev.shale.KeyComparator;
import dev.shale.internal.coding.LittleEndian;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Reading a block back: reconstruct full keys from the prefix-compressed deltas, iterate in order,
 * and {@code seek} to the first key ≥ a target via the restart-point binary search (format.md §2).
 * The block reader is comparator-generic; here it is exercised with {@link BytewiseComparator}.
 */
class BlockTest {

  private static final KeyComparator BYTEWISE = BytewiseComparator.INSTANCE;

  @Test
  void iteratesEntriesInOrder_reconstructingKeys() {
    List<String> keys = keys(20);
    Block block = new Block(build(keys));

    Block.Iterator iterator = block.iterator(BYTEWISE);
    List<String> read = new ArrayList<>();
    for (iterator.seekToFirst(); iterator.valid(); iterator.next()) {
      assertThat(value(iterator)).isEqualTo("v" + key(iterator)); // value tracks its key
      read.add(key(iterator));
    }
    assertThat(read).isEqualTo(keys);
  }

  @Test
  void seek_landsOnFirstKeyAtOrAfterTarget() {
    Block block = new Block(build(keys(20)));
    Block.Iterator iterator = block.iterator(BYTEWISE);

    iterator.seek(ascii("k0005")); // exact
    assertThat(key(iterator)).isEqualTo("k0005");

    iterator.seek(ascii("k0005x")); // between k0005 and k0006
    assertThat(key(iterator)).isEqualTo("k0006");

    iterator.seek(ascii("a")); // before all
    assertThat(key(iterator)).isEqualTo("k0000");

    iterator.seek(ascii("z")); // after all
    assertThat(iterator.valid()).isFalse();
  }

  @Test
  void emptyBlock_hasNoEntries() {
    Block block = new Block(new BlockBuilder(16).finish());

    Block.Iterator iterator = block.iterator(BYTEWISE);
    iterator.seekToFirst();

    assertThat(iterator.valid()).isFalse();
  }

  @Test
  void blockTooSmallToHoldARestartCount_isCorruption() {
    assertThatThrownBy(() -> new Block(new byte[] {0x00, 0x00}))
        .isInstanceOf(CorruptionException.class);
  }

  @Test
  void restartCountThatOverrunsTheBlock_isCorruption() {
    // num_restarts = 1000 but the block is tiny → the restart array cannot fit.
    byte[] content = new byte[] {0x00, 0x00, 0x00, 0x00, (byte) 0xE8, 0x03, 0x00, 0x00};
    assertThatThrownBy(() -> new Block(content)).isInstanceOf(CorruptionException.class);
  }

  @Test
  void sh14_restartCountOverflowingTheOffsetArithmetic_isCorruption() {
    // SH-14: a 24-byte block whose count is 0x40000001. (1 + count) * 4 overflowed int to 8, so
    // the restart array appeared to sit at offset 16 and the block parsed, its first key readable.
    byte[] content = new byte[24];
    byte[] entry = {0x00, 0x01, 0x01, 'A', 'x'}; // shared 0, non-shared 1, value 1, "A" -> "x"
    System.arraycopy(entry, 0, content, 0, entry.length);
    LittleEndian.putFixed32(content, 16, 0); // restart[0] = 0
    LittleEndian.putFixed32(content, 20, 0x40000001);

    assertThatThrownBy(() -> new Block(content))
        .isExactlyInstanceOf(CorruptionException.class)
        .hasMessageContaining("restart count");
  }

  @Test
  void negativeRestartCount_isCorruption() {
    byte[] content = build(keys(3));
    LittleEndian.putFixed32(content, content.length - 4, -1);

    assertThatThrownBy(() -> new Block(content)).isExactlyInstanceOf(CorruptionException.class);
  }

  @Test
  void restartOffsetOutsideTheEntryRegion_isCorruption() {
    byte[] content = build(keys(20));
    setRestart(content, 1, content.length); // past the end of the block

    assertThatThrownBy(() -> new Block(content))
        .isExactlyInstanceOf(CorruptionException.class)
        .hasMessageContaining("restart offset");
  }

  @Test
  void restartOffsetsOutOfOrder_isCorruption() {
    byte[] content = build(keys(20));
    int second = restart(content, 1);
    setRestart(content, 1, restart(content, 2));
    setRestart(content, 2, second);

    assertThatThrownBy(() -> new Block(content))
        .isExactlyInstanceOf(CorruptionException.class)
        .hasMessageContaining("restart offset");
  }

  @Test
  void firstRestartNotAtTheStart_isCorruption() {
    byte[] content = build(keys(20));
    setRestart(content, 0, restart(content, 1));

    assertThatThrownBy(() -> new Block(content)).isExactlyInstanceOf(CorruptionException.class);
  }

  @Test
  void restartPointingAtAnEntryThatSharesAPrefix_isCorruption() {
    // keys(20) with interval 4: entry 0 ("k0000" -> "vk0000") is 3 + 5 + 6 = 14 bytes, so entry 1
    // starts at 14 and shares "k000" (shared = 4) with it: not a restart point.
    byte[] content = build(keys(20));
    assertThat(content[14]).as("entry 1's shared length").isEqualTo((byte) 4);
    setRestart(content, 1, 14);

    assertThatThrownBy(() -> new Block(content))
        .isExactlyInstanceOf(CorruptionException.class)
        .hasMessageContaining("shared");
  }

  @Test
  void zeroRestartsAndNoEntries_isAnEmptyBlock() {
    Block block = new Block(new byte[4]); // count 0, nothing else

    Block.Iterator iterator = block.iterator(BYTEWISE);
    iterator.seekToFirst();

    assertThat(iterator.valid()).isFalse();
  }

  private static int restartCount(byte[] content) {
    return LittleEndian.getFixed32(content, content.length - 4);
  }

  private static int restartSlot(byte[] content, int index) {
    return content.length - 4 - (restartCount(content) - index) * 4;
  }

  private static int restart(byte[] content, int index) {
    return LittleEndian.getFixed32(content, restartSlot(content, index));
  }

  private static void setRestart(byte[] content, int index, int offset) {
    LittleEndian.putFixed32(content, restartSlot(content, index), offset);
  }

  private static byte[] build(List<String> keys) {
    BlockBuilder builder = new BlockBuilder(4); // small interval to exercise several restarts
    for (String key : keys) {
      builder.add(ascii(key), ascii("v" + key));
    }
    return builder.finish();
  }

  private static List<String> keys(int count) {
    List<String> keys = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      keys.add(String.format("k%04d", i));
    }
    return keys;
  }

  private static String key(Block.Iterator iterator) {
    return new String(iterator.key(), StandardCharsets.US_ASCII);
  }

  private static String value(Block.Iterator iterator) {
    return new String(iterator.value(), StandardCharsets.US_ASCII);
  }

  private static byte[] ascii(String value) {
    return value.getBytes(StandardCharsets.US_ASCII);
  }
}
