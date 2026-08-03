package dev.shale;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Flush (ADR-0010, M3): when the active memtable fills it is written to an SSTable and its WAL
 * segment is reclaimed. Reads must see flushed data, a memtable tombstone must shadow a flushed
 * value, and reopening must recover everything from the tables plus any remaining log.
 */
class ShaleFlushTest {

  private static final long TINY_BUFFER_BYTES = 256; // small, to force flushes quickly

  @TempDir private Path dir;

  @Test
  void fillingTheBuffer_writesSSTablesAndReclaimsWalSegments() throws IOException {
    RecordingMetrics metrics = new RecordingMetrics();
    try (Shale db = open(metrics)) {
      for (int i = 0; i < 300; i++) {
        db.put(key(i), value(i), Durability.NONE);
      }
      assertThat(metrics.counter("flush.count")).isGreaterThan(0);
    }
    assertThat(files(".sst")).as("flushed tables").isNotEmpty();
    assertThat(files(".wal")).as("only the active segment remains").hasSize(1);
  }

  @Test
  void valuesFlushedToSSTables_readBack() throws IOException {
    try (Shale db = open(new RecordingMetrics())) {
      for (int i = 0; i < 300; i++) {
        db.put(key(i), value(i), Durability.NONE);
      }
      for (int i = 0; i < 300; i++) {
        assertThat(db.get(key(i))).as("key %d", i).isEqualTo(value(i));
      }
      assertThat(db.get(bytes("absent"))).isNull();
    }
  }

  @Test
  void memtableTombstone_shadowsAFlushedValue() throws IOException {
    try (Shale db = open(new RecordingMetrics())) {
      db.put(key(0), value(0), Durability.NONE); // will be flushed to an SSTable
      for (int i = 1; i < 300; i++) {
        db.put(key(i), value(i), Durability.NONE); // force flushes; key 0 lands in a table
      }
      db.delete(key(0), Durability.NONE); // tombstone lives in the active memtable

      assertThat(db.get(key(0))).isNull();
    }
  }

  @Test
  void scan_mergesMemtableAndSSTables() throws IOException {
    try (Shale db = open(new RecordingMetrics())) {
      for (int i = 0; i < 300; i++) {
        db.put(key(i), value(i), Durability.NONE);
      }
      assertThat(scanKeyCount(db)).isEqualTo(300);
    }
  }

  @Test
  void reopenAfterFlushes_recoversEveryWrite() throws IOException {
    try (Shale db = open(new RecordingMetrics())) {
      for (int i = 0; i < 300; i++) {
        db.put(key(i), value(i), Durability.SYNC);
      }
      db.delete(key(7), Durability.SYNC);
    }
    try (Shale reopened = open(new RecordingMetrics())) {
      for (int i = 0; i < 300; i++) {
        if (i == 7) {
          assertThat(reopened.get(key(i))).as("deleted key 7").isNull();
        } else {
          assertThat(reopened.get(key(i))).as("key %d after reopen", i).isEqualTo(value(i));
        }
      }
    }
  }

  private Shale open(RecordingMetrics metrics) throws IOException {
    return Shale.open(dir, Clock.system(), metrics, TINY_BUFFER_BYTES);
  }

  private List<Path> files(String suffix) throws IOException {
    try (Stream<Path> entries = Files.list(dir)) {
      return entries.filter(p -> p.getFileName().toString().endsWith(suffix)).toList();
    }
  }

  private static int scanKeyCount(Shale db) {
    int count = 0;
    try (Cursor cursor = db.scan(null, null)) {
      while (cursor.isValid()) {
        count++;
        cursor.next();
      }
    }
    return count;
  }

  private static byte[] key(int i) {
    return bytes(String.format("k%05d", i));
  }

  private static byte[] value(int i) {
    return bytes("value-of-" + i);
  }

  private static byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.US_ASCII);
  }
}
