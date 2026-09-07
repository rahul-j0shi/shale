package dev.shale;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Crash recovery: truncate the WAL at every byte offset — the space of "the process died
 * mid-append" outcomes — and assert the engine reopens with exactly the records whose bytes
 * survived (testing.md §1, N4).
 *
 * <p>The assertion has two halves, and the second is the one that matters. The upper bound says
 * recovery invents nothing: what comes back is a prefix of what was written, in order, with the
 * right values. The lower bound says recovery loses nothing it could have kept: every record whose
 * bytes lie wholly below the truncation point <em>must</em> come back, because under {@code SYNC}
 * each was forced to disk before its {@code put} returned and was therefore acknowledged (D3). An
 * upper bound alone is satisfied by an engine that recovers nothing at all, which is why the two
 * are asserted together as an equality.
 *
 * <p>Record boundaries come from the file size after each append rather than from a test hook on
 * {@code WalWriter}: the writer appends one record per call, so its growth <em>is</em> the record
 * boundary, and no production instrumentation exists solely to be asserted on.
 *
 * <p><b>Failure model:</b> this covers process crash — the space of prefixes a dead process can
 * leave. Power-loss durability rests on the ordering argument (force before ack), not on this test
 * (concurrency-and-resources.md D5).
 */
@Tag("crash")
class ShaleCrashTest {

  /** Magic + version + reserved; a torn header predates any acknowledged record. */
  private static final int WAL_FILE_HEADER_SIZE = 16;

  @TempDir private Path dir;

  @Test
  void truncatingTailAtEveryOffset_recoversExactlyTheCompleteRecords() throws IOException {
    Map<String, String> written = new LinkedHashMap<>();
    for (int i = 0; i < 5; i++) {
      written.put("k" + i, "v-" + i);
    }

    Path source = dir.resolve("source");
    Path segment = source.resolve("000001.wal");
    List<String> keys = List.copyOf(written.keySet());
    long[] endOffsets = new long[keys.size()];

    try (Shale db = Shale.open(source, Clock.system(), Metrics.NOOP)) {
      for (int i = 0; i < keys.size(); i++) {
        String key = keys.get(i);
        db.put(bytes(key), bytes(written.get(key)), Durability.SYNC);
        // The append is forced before put returns, so the segment's size now marks exactly where
        // this record ends. A truncation at or past it leaves the record whole.
        endOffsets[i] = Files.size(segment);
      }
    }
    byte[] full = Files.readAllBytes(segment);

    // Start past the 16-byte file header (magic + version); a torn header means no record was
    // ever acknowledged, a separate degenerate case.
    for (int length = WAL_FILE_HEADER_SIZE; length <= full.length; length++) {
      Map<String, String> recovered = recoverFromPrefix(full, length);

      int complete = 0;
      while (complete < endOffsets.length && endOffsets[complete] <= length) {
        complete++;
      }
      List<String> expectedKeys = keys.subList(0, complete);

      assertThat(recovered.keySet())
          .as(
              "truncated at %d of %d: every record ending at or before it survives",
              length, full.length)
          .containsExactlyElementsOf(expectedKeys);
      for (String key : expectedKeys) {
        assertThat(recovered.get(key))
            .as("value of %s after truncation at %d", key, length)
            .isEqualTo(written.get(key));
      }
    }
  }

  private Map<String, String> recoverFromPrefix(byte[] full, int length) throws IOException {
    Path victim = dir.resolve("t" + length);
    Files.createDirectories(victim);
    Files.write(victim.resolve("000001.wal"), Arrays.copyOf(full, length));
    try (Shale db = Shale.open(victim, Clock.system(), Metrics.NOOP)) {
      return liveEntries(db);
    }
  }

  private static Map<String, String> liveEntries(Shale db) {
    Map<String, String> entries = new LinkedHashMap<>();
    try (Cursor cursor = db.scan(null, null)) {
      while (cursor.isValid()) {
        entries.put(text(cursor.key()), text(cursor.value()));
        cursor.next();
      }
    }
    return entries;
  }

  private static String text(byte[] value) {
    return new String(value, StandardCharsets.US_ASCII);
  }

  private static byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.US_ASCII);
  }
}
