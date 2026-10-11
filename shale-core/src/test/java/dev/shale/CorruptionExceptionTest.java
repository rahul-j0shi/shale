package dev.shale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import dev.shale.internal.key.InternalKeyComparator;
import dev.shale.sstable.SSTableReader;
import dev.shale.wal.WalReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CorruptionExceptionTest {

  @TempDir private Path dir;

  @Test
  void message_carriesOffsetAndValues() {
    CorruptionException e = new CorruptionException("unknown value type", 12, 1, 255);

    assertThat(e.getMessage()).contains("unknown value type");
    assertThat(e.offsetBytes()).isEqualTo(12);
    assertThat(e.expectedValue()).isEqualTo(1);
    assertThat(e.actualValue()).isEqualTo(255);
    assertThat(e).isInstanceOf(ShaleException.class);
  }

  @Test
  void withAFile_theMessageNamesIt() {
    Path file = Path.of("db", "000012.sst");
    CorruptionException e = new CorruptionException(file, "SSTable block CRC mismatch", 40, 7, 9);

    assertThat(e.getMessage()).startsWith(file + ": SSTable block CRC mismatch");
    assertThat(e.file()).contains(file);
    assertThat(new CorruptionException("no file").file()).isEmpty();
  }

  @Test
  void inFile_keepsTheEvidence_andTheDecodersExceptionAsTheCause() {
    CorruptionException decoder = new CorruptionException("varint overflows 64 bits", 3, 1, 2);
    Path file = Path.of("000001.wal");

    CorruptionException located = decoder.inFile(file);

    assertThat(located.getMessage()).contains("000001.wal").contains("varint overflows 64 bits");
    assertThat(located.offsetBytes()).isEqualTo(3);
    assertThat(located.expectedValue()).isEqualTo(1);
    assertThat(located.actualValue()).isEqualTo(2);
    assertThat(located.getCause()).isSameAs(decoder);
  }

  @Test
  void aDamagedWalSegment_isReportedWithItsPath() throws IOException {
    try (Shale db = Shale.open(dir, Clock.system(), Metrics.NOOP)) {
      db.put(ascii("k"), ascii("v"), Durability.SYNC);
    }
    Path segment = dir.resolve("000001.wal");
    flipByte(segment, 30); // inside the first record's payload

    CorruptionException e =
        catchThrowableOfType(CorruptionException.class, () -> WalReader.read(segment));

    assertThat(e.file()).contains(segment);
    assertThat(e.getMessage()).contains(segment.toString());
  }

  @Test
  void aDamagedTable_isReportedWithItsPath() throws IOException {
    try (Shale db = Shale.open(dir, Clock.system(), Metrics.NOOP, 64)) {
      db.put(ascii("k"), new byte[100], Durability.SYNC); // fills the buffer: one flushed table
    }
    Path table = dir.resolve("000003.sst"); // the switch takes 000002 for the new segment
    flipByte(table, 10); // inside the first data block

    try (SSTableReader reader =
        SSTableReader.open(table, new InternalKeyComparator(BytewiseComparator.INSTANCE))) {
      CorruptionException e =
          catchThrowableOfType(CorruptionException.class, () -> reader.iterator().seekToFirst());

      assertThat(e.file()).contains(table);
    }
  }

  private static void flipByte(Path file, int offset) throws IOException {
    byte[] bytes = Files.readAllBytes(file);
    bytes[offset] ^= 0x01;
    Files.write(file, bytes);
  }

  private static byte[] ascii(String text) {
    return text.getBytes(StandardCharsets.US_ASCII);
  }
}
