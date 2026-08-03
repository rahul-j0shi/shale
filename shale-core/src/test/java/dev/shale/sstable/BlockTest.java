package dev.shale.sstable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.shale.BytewiseComparator;
import dev.shale.CorruptionException;
import dev.shale.KeyComparator;
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
