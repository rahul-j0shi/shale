package dev.shale.wal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.shale.Clock;
import dev.shale.CorruptionException;
import dev.shale.Durability;
import dev.shale.Metrics;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
    byte[] big = pattern(WalFormat.BLOCK_SIZE * 2 + 100);
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
