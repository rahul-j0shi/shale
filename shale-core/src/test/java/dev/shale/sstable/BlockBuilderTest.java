package dev.shale.sstable;

import static org.assertj.core.api.Assertions.assertThat;

import dev.shale.internal.coding.LittleEndian;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * Byte-level checks on the block encoding (format.md §2): prefix compression against the previous
 * key, a restart point every {@code restartInterval} entries, and the trailing restart array +
 * count. Semantic round-tripping is covered once the block reader exists.
 */
class BlockBuilderTest {

  @Test
  void twoEntries_shareTheirCommonPrefix() {
    BlockBuilder builder = new BlockBuilder(16);
    builder.add(ascii("apple"), ascii("1"));
    builder.add(ascii("apply"), ascii("2")); // shares "appl" (4 bytes) with "apple"

    byte[] content = builder.finish();

    byte[] expected =
        concat(
            // entry 0 (restart): shared=0, non_shared=5, value_len=1, "apple", "1"
            new byte[] {0x00, 0x05, 0x01},
            ascii("apple"),
            ascii("1"),
            // entry 1: shared=4, non_shared=1, value_len=1, "y", "2"
            new byte[] {0x04, 0x01, 0x01},
            ascii("y"),
            ascii("2"),
            // restarts: [0]
            fixed32(0),
            // num_restarts = 1
            fixed32(1));
    assertThat(content).isEqualTo(expected);
  }

  @Test
  void restartPoint_everyInterval() {
    BlockBuilder builder = new BlockBuilder(16);
    for (int i = 0; i < 17; i++) {
      builder.add(ascii(String.format("k%03d", i)), ascii("v"));
    }

    byte[] content = builder.finish();

    // last 4 bytes are num_restarts; entry 0 and entry 16 are restarts → 2.
    int numRestarts = LittleEndian.getFixed32(content, content.length - 4);
    assertThat(numRestarts).isEqualTo(2);
  }

  @Test
  void empty_hasOneRestartAtZeroAndNoEntries() {
    BlockBuilder builder = new BlockBuilder(16);

    assertThat(builder.isEmpty()).isTrue();
    // LevelDB keeps restart[0]=0 always, so an empty block is restart[0]=0 then num_restarts=1;
    // the reader bounds the (empty) entry region by the restart offset.
    assertThat(builder.finish()).containsExactly(0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00);
  }

  private static byte[] ascii(String value) {
    return value.getBytes(StandardCharsets.US_ASCII);
  }

  private static byte[] fixed32(int value) {
    byte[] out = new byte[4];
    LittleEndian.putFixed32(out, 0, value);
    return out;
  }

  private static byte[] concat(byte[]... parts) {
    int length = 0;
    for (byte[] part : parts) {
      length += part.length;
    }
    byte[] out = new byte[length];
    int offset = 0;
    for (byte[] part : parts) {
      System.arraycopy(part, 0, out, offset, part.length);
      offset += part.length;
    }
    return out;
  }
}
