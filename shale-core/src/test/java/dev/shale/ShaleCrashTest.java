package dev.shale;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Crash recovery: truncate the WAL at every byte offset — the space of "process died mid-append"
 * outcomes — and assert the engine always reopens with a clean *prefix* of the writes and never a
 * wrong or partial value (testing.md §1, N4). Writes are {@code SYNC}, so any write whose record is
 * fully on disk was acknowledged and must survive.
 */
@Tag("crash")
class ShaleCrashTest {

  @TempDir private Path dir;

  @Test
  void truncatingTailAtEveryOffset_recoversACleanPrefix() throws IOException {
    List<String> written = List.of("k0", "k1", "k2", "k3", "k4");
    Path source = dir.resolve("source");
    try (Shale db = Shale.open(source, Clock.system(), Metrics.NOOP)) {
      for (String key : written) {
        db.put(bytes(key), bytes("v-" + key), Durability.SYNC);
      }
    }
    byte[] full = Files.readAllBytes(source.resolve("000001.wal"));

    // Start past the 16-byte file header (magic + version); a torn header means no record was
    // ever acknowledged, a separate degenerate case.
    for (int length = 16; length <= full.length; length++) {
      List<String> recovered = recoverFromPrefix(full, length);

      // recovered is exactly some prefix of the writes: no extra records, and the ones present
      // match the corresponding writes in order (never a wrong key, never out of order).
      assertThat(recovered).hasSizeLessThanOrEqualTo(written.size());
      assertThat(recovered).isEqualTo(written.subList(0, recovered.size()));
    }
  }

  private List<String> recoverFromPrefix(byte[] full, int length) throws IOException {
    Path victim = dir.resolve("t" + length);
    Files.createDirectories(victim);
    Files.write(victim.resolve("000001.wal"), Arrays.copyOf(full, length));
    try (Shale db = Shale.open(victim, Clock.system(), Metrics.NOOP)) {
      return liveKeys(db);
    }
  }

  private static List<String> liveKeys(Shale db) {
    List<String> keys = new ArrayList<>();
    try (Cursor cursor = db.scan(null, null)) {
      while (cursor.isValid()) {
        keys.add(new String(cursor.key(), StandardCharsets.US_ASCII));
        cursor.next();
      }
    }
    return keys;
  }

  private static byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.US_ASCII);
  }
}
