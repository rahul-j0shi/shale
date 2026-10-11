package dev.shale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.shale.internal.key.InternalKey;
import dev.shale.internal.key.ValueType;
import dev.shale.sstable.SSTableWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * File numbers past six digits (SH-09, M5 Step 4). Names are written {@code %06d}, a minimum width,
 * so number 1,000,000 has seven digits; discovery must accept {@code \d{6,}} and order by the
 * number, not the string, or a long-lived database loses its newest files on reopen.
 */
class ShaleFileNumberTest {

  @TempDir private Path dir;

  @Test
  void reviewHistory_aWalRenumberedTo999999_keepsItsWriteAcrossTwoReopens() throws IOException {
    try (Shale db = Shale.open(dir, Clock.system(), Metrics.NOOP)) {
      db.put(ascii("K"), ascii("v"), Durability.SYNC);
    }
    Files.move(dir.resolve("000001.wal"), dir.resolve("999999.wal"));

    for (int reopen = 1; reopen <= 2; reopen++) {
      try (Shale db = Shale.open(dir, Clock.system(), Metrics.NOOP)) {
        assertThat(db.get(ascii("K"))).as("after reopen %d", reopen).isEqualTo(ascii("v"));
      }
    }
  }

  @Test
  void tablesAcrossTheSixDigitBoundary_areReadNewestNumberFirst() throws IOException {
    writeTable("999998.sst", 1, "oldest");
    writeTable("999999.sst", 2, "older");
    writeTable("1000000.sst", 3, "newest");

    try (Shale db = Shale.open(dir, Clock.system(), Metrics.NOOP)) {
      assertThat(db.get(ascii("k"))).isEqualTo(ascii("newest"));
    }
  }

  @Test
  void namesThatAreNotEngineFiles_areLeftAloneAndNeverParsed() throws IOException {
    byte[] junk = {1, 2, 3};
    Files.write(dir.resolve("12345.sst"), junk);
    Files.write(dir.resolve("000001.sst.bak"), junk);

    try (Shale db = Shale.open(dir, Clock.system(), Metrics.NOOP)) {
      db.put(ascii("k"), ascii("v"), Durability.SYNC);
    }

    assertThat(Files.readAllBytes(dir.resolve("12345.sst"))).isEqualTo(junk);
    assertThat(Files.readAllBytes(dir.resolve("000001.sst.bak"))).isEqualTo(junk);
  }

  @Test
  void aCounterThatWouldPassLongMaxValue_isRefused() throws IOException {
    Files.write(dir.resolve(Long.MAX_VALUE + ".wal"), new byte[0]); // an empty, clean segment

    assertThatThrownBy(() -> Shale.open(dir, Clock.system(), Metrics.NOOP))
        .isExactlyInstanceOf(EngineStateException.class)
        .hasMessageContaining("file number");
    assertThat(dir.resolve(Long.MAX_VALUE + ".wal")).as("a refused open deletes nothing").exists();
  }

  @Test
  void aNumberTooLargeForALong_isRefused() throws IOException {
    Files.write(dir.resolve("99999999999999999999.wal"), new byte[0]);

    assertThatThrownBy(() -> Shale.open(dir, Clock.system(), Metrics.NOOP))
        .isExactlyInstanceOf(EngineStateException.class)
        .hasMessageContaining("99999999999999999999.wal");
  }

  private void writeTable(String name, long sequence, String value) throws IOException {
    try (SSTableWriter writer = SSTableWriter.open(dir.resolve(name))) {
      writer.add(new InternalKey(ascii("k"), sequence, ValueType.PUT).encode(), ascii(value));
      writer.finish();
    }
  }

  private static byte[] ascii(String text) {
    return text.getBytes(StandardCharsets.US_ASCII);
  }
}
