package dev.shale.wal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.shale.Clock;
import dev.shale.CorruptionException;
import dev.shale.Durability;
import dev.shale.Metrics;
import dev.shale.internal.log.LogFormat;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class WalTest {

  @TempDir private Path dir;

  @Test
  void roundTrip_smallRecords() throws IOException {
    Path path = write(List.of(ascii("a"), ascii("bb"), ascii("ccc")), Durability.NONE);

    List<byte[]> back = WalReader.readAll(path, RecoveryPolicy.STRICT);

    assertThat(back).hasSize(3);
    assertThat(back.get(0)).isEqualTo(ascii("a"));
    assertThat(back.get(1)).isEqualTo(ascii("bb"));
    assertThat(back.get(2)).isEqualTo(ascii("ccc"));
  }

  @Test
  void roundTrip_recordSpanningManyBlocks() throws IOException {
    byte[] big = pattern(LogFormat.BLOCK_SIZE * 2 + 100);
    Path path = write(List.of(ascii("head"), big, ascii("tail")), Durability.NONE);

    List<byte[]> back = WalReader.readAll(path, RecoveryPolicy.STRICT);

    assertThat(back).hasSize(3);
    assertThat(back.get(1)).isEqualTo(big);
    assertThat(back.get(2)).isEqualTo(ascii("tail"));
  }

  @Test
  void sync_appendsAreReadableBack() throws IOException {
    Path path = write(List.of(ascii("durable")), Durability.SYNC);

    assertThat(WalReader.readAll(path, RecoveryPolicy.STRICT).get(0)).isEqualTo(ascii("durable"));
  }

  @Test
  void truncatedTail_truncatePolicyReturnsCleanPrefix() throws IOException {
    Path path = write(List.of(ascii("one"), ascii("two"), ascii("three")), Durability.NONE);
    truncate(path, Files.size(path) - 2); // cut into the last record

    List<byte[]> back = WalReader.readAll(path, RecoveryPolicy.TRUNCATE_TAIL);

    assertThat(back).hasSize(2);
    assertThat(back.get(0)).isEqualTo(ascii("one"));
    assertThat(back.get(1)).isEqualTo(ascii("two"));
  }

  @Test
  void truncatedTail_strictPolicyThrows() throws IOException {
    Path path = write(List.of(ascii("one"), ascii("two")), Durability.NONE);
    truncate(path, Files.size(path) - 2);

    assertThatThrownBy(() -> WalReader.readAll(path, RecoveryPolicy.STRICT))
        .isInstanceOf(CorruptionException.class);
  }

  @Test
  void bitFlipInPayload_isCorruptionNotTornTail() throws IOException {
    Path path = write(List.of(ascii("hello")), Durability.NONE);
    flipByte(path, Files.size(path) - 1); // a present byte turned wrong, not a missing byte

    assertThatThrownBy(() -> WalReader.readAll(path, RecoveryPolicy.TRUNCATE_TAIL))
        .isInstanceOf(CorruptionException.class);
  }

  @Test
  void badMagic_isCorruption() throws IOException {
    Path path = write(List.of(ascii("x")), Durability.NONE);
    flipByte(path, 0);

    assertThatThrownBy(() -> WalReader.readAll(path, RecoveryPolicy.TRUNCATE_TAIL))
        .isInstanceOf(CorruptionException.class);
  }

  @Test
  void v1_fullToZeroTypeBit_isCorruptionUnderBothPolicies() throws IOException {
    // SH-08: bit 0 of byte 22 turns the first fragment's type FULL (1) into ZERO (0). The reader
    // took ZERO as padding and skipped the block before any CRC check, so both acknowledged
    // records vanished and recovery reported a clean, empty log.
    Path path = v1Golden("two-puts.wal");
    flipBit(path, 22, 0);

    assertThatThrownBy(() -> WalReader.readAll(path, RecoveryPolicy.TRUNCATE_TAIL))
        .isInstanceOf(CorruptionException.class)
        .hasMessageContaining("ZERO");
    assertThatThrownBy(() -> WalReader.readAll(path, RecoveryPolicy.STRICT))
        .isInstanceOf(CorruptionException.class);
  }

  @ParameterizedTest(name = "{0} zero bytes")
  @ValueSource(ints = {7, 100, 40_000})
  void v1_zeroFilledTail_isAnUnwrittenTailNotCorruption(int zeros) throws IOException {
    // A filesystem can expose space it allocated but never wrote as zeros after a crash. Every
    // byte from the header to the end of the file is zero, so nothing was ever written there.
    Path path = v1Golden("two-puts.wal");
    appendZeros(path, zeros);

    List<byte[]> records = WalReader.readAll(path, RecoveryPolicy.TRUNCATE_TAIL);

    assertThat(records).hasSize(2);
    assertThatThrownBy(() -> WalReader.readAll(path, RecoveryPolicy.STRICT))
        .isInstanceOf(CorruptionException.class);
  }

  @Test
  void v1_nonZeroBlockPadding_isCorruption() throws IOException {
    // block-padding.wal's block 0 ends with 3 padding bytes at offset 32781; they must be zero.
    Path path = v1Golden("block-padding.wal");
    flipBit(path, 32_782, 3);

    assertThatThrownBy(() -> WalReader.readAll(path, RecoveryPolicy.TRUNCATE_TAIL))
        .isInstanceOf(CorruptionException.class)
        .hasMessageContaining("padding");
  }

  private Path v1Golden(String name) throws IOException {
    Path path = dir.resolve(name);
    Files.copy(Path.of("src/test/resources/golden/wal/v1/" + name), path);
    return path;
  }

  private static void flipBit(Path path, int offset, int bit) throws IOException {
    byte[] data = Files.readAllBytes(path);
    data[offset] ^= (byte) (1 << bit);
    Files.write(path, data);
  }

  private static void appendZeros(Path path, int count) throws IOException {
    Files.write(path, new byte[count], StandardOpenOption.APPEND);
  }

  private Path write(List<byte[]> payloads, Durability durability) throws IOException {
    Path path = dir.resolve("000001.wal");
    try (WalWriter writer = WalWriter.open(path, Clock.system(), Metrics.NOOP)) {
      for (byte[] payload : payloads) {
        writer.append(payload, durability);
      }
    }
    return path;
  }

  private static byte[] ascii(String value) {
    return value.getBytes(StandardCharsets.US_ASCII);
  }

  private static byte[] pattern(int length) {
    byte[] bytes = new byte[length];
    for (int i = 0; i < length; i++) {
      bytes[i] = (byte) (i * 31 + 7);
    }
    return bytes;
  }

  private static void truncate(Path path, long size) throws IOException {
    try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
      channel.truncate(size);
    }
  }

  private static void flipByte(Path path, long offset) throws IOException {
    byte[] data = Files.readAllBytes(path);
    data[(int) offset] ^= (byte) 0xFF;
    Files.write(path, data);
  }
}
