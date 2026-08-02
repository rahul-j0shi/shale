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
 * Memory accounting and the active → immutable memtable handoff (ADR-0009, M2). A small write
 * buffer forces switches; the engine must keep every value readable across memtables, roll to a new
 * WAL segment on each switch, and recover the whole logical state on reopen.
 */
class ShaleMemtableSwitchTest {

  private static final long TINY_BUFFER_BYTES = 128;

  @TempDir private Path dir;

  @Test
  void writingPastBuffer_triggersSwitchesAndRollsSegments() throws IOException {
    RecordingMetrics metrics = new RecordingMetrics();
    try (Shale db = open(metrics)) {
      for (int i = 0; i < 200; i++) {
        db.put(key(i), value(i), Durability.NONE);
      }
      assertThat(metrics.counter("memtable.switch.count")).isGreaterThan(0);
    }
    // Each switch rolls a new segment, so more than one WAL file exists on disk.
    assertThat(walSegments()).hasSizeGreaterThan(1);
  }

  @Test
  void valuesWrittenAcrossSwitches_allReadBack() throws IOException {
    try (Shale db = open(new RecordingMetrics())) {
      for (int i = 0; i < 200; i++) {
        db.put(key(i), value(i), Durability.NONE);
      }
      for (int i = 0; i < 200; i++) {
        assertThat(db.get(key(i))).as("key %d after switches", i).isEqualTo(value(i));
      }
    }
  }

  @Test
  void overwriteOfAKeyStrandedInAnImmutable_returnsNewestValue() throws IOException {
    try (Shale db = open(new RecordingMetrics())) {
      db.put(key(0), value(0), Durability.NONE); // lands in the first (soon immutable) memtable
      for (int i = 1; i < 200; i++) {
        db.put(key(i), value(i), Durability.NONE); // force switches; key 0 is left behind
      }
      db.put(key(0), bytes("rewritten"), Durability.NONE); // newest version in the active memtable

      assertThat(db.get(key(0))).isEqualTo(bytes("rewritten"));
    }
  }

  @Test
  void reopenAfterSwitches_recoversEveryWrite() throws IOException {
    try (Shale db = open(new RecordingMetrics())) {
      for (int i = 0; i < 200; i++) {
        db.put(key(i), value(i), Durability.SYNC);
      }
    }
    try (Shale reopened = open(new RecordingMetrics())) {
      for (int i = 0; i < 200; i++) {
        assertThat(reopened.get(key(i))).as("key %d after reopen", i).isEqualTo(value(i));
      }
    }
  }

  private Shale open(RecordingMetrics metrics) throws IOException {
    return Shale.open(dir, Clock.system(), metrics, TINY_BUFFER_BYTES);
  }

  private List<Path> walSegments() throws IOException {
    try (Stream<Path> entries = Files.list(dir)) {
      return entries.filter(p -> p.getFileName().toString().endsWith(".wal")).sorted().toList();
    }
  }

  private static byte[] key(int i) {
    return bytes(String.format("k%04d", i));
  }

  private static byte[] value(int i) {
    return bytes("v-" + i);
  }

  private static byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.US_ASCII);
  }
}
