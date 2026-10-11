package dev.shale;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Writes {@code golden/db/m4-layout/}: a database directory as the engine left it before the
 * manifest (M5 Step 7.1) — two tables and one WAL segment with records not yet flushed, and no
 * {@code CURRENT}. It was run once, by hand, with the engine as it stood before {@code open}
 * learned the manifest, and its output committed; it cannot be rerun to the same effect since.
 * {@code ShaleManifestRecoveryTest} opens a copy and expects every record in {@code
 * m4-layout.json}.
 *
 * <pre>{@code
 * java -cp build/classes/java/main:build/classes/java/test \
 *     dev.shale.M4LayoutFixtureGenerator <output-directory>
 * }</pre>
 */
final class M4LayoutFixtureGenerator {

  private M4LayoutFixtureGenerator() {}

  public static void main(String[] args) throws IOException {
    Path out = Path.of(args[0]);
    // A 100-byte buffer: puts 1–3 fill one table, 4–6 the next; put 7 and a delete stay in the WAL.
    try (Shale db = Shale.open(out, Clock.system(), Metrics.NOOP, 100)) {
      for (int i = 1; i <= 7; i++) {
        db.put(ascii("key-" + i), ascii("value-" + i + "-".repeat(20)), Durability.SYNC);
      }
      db.delete(ascii("key-2"), Durability.SYNC); // a tombstone in the WAL over a flushed key
    }
    Files.delete(out.resolve("LOCK")); // an M4 directory had none
  }

  private static byte[] ascii(String text) {
    return text.getBytes(StandardCharsets.US_ASCII);
  }
}
