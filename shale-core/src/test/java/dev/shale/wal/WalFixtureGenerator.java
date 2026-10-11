package dev.shale.wal;

import dev.shale.Clock;
import dev.shale.Durability;
import dev.shale.Metrics;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Writes the golden WAL fixtures with the <em>current</em> {@link WalWriter}. It is run by hand,
 * once per format version, and its output is committed under {@code golden/wal/v<N>/}; the
 * committed files are then frozen and never regenerated (on-disk-formats.md §4). The v1 goldens
 * were written by the M4 writer before M4.5 changed the framing ({@code single-put.wal} at M1, the
 * rest at M4.5 Step 2); the v2 goldens by the M4.5 writer.
 *
 * <p>Run it from {@code shale-core} after {@code ./gradlew :shale-core:testClasses}:
 *
 * <pre>{@code
 * java -cp build/classes/java/main:build/classes/java/test \
 *     dev.shale.wal.WalFixtureGenerator <output-directory>
 * }</pre>
 *
 * It writes each fixture's {@code .wal} only; the {@code .json} descriptions are written by hand
 * from {@link WalFixtures} and the record end offsets this class checks.
 */
final class WalFixtureGenerator {

  private WalFixtureGenerator() {}

  public static void main(String[] args) throws IOException {
    Path out = Path.of(args[0]);
    Files.createDirectories(out);
    write(out.resolve("single-put.wal"), WalFixtures.SINGLE_PUT);
    write(out.resolve("two-puts.wal"), WalFixtures.TWO_PUTS);
    write(out.resolve("fragmented.wal"), WalFixtures.FRAGMENTED);
    write(out.resolve("block-padding.wal"), WalFixtures.blockPadding(WalFormat.FORMAT_VERSION));
  }

  private static void write(Path path, List<WalFixtures.Mutation> mutations) throws IOException {
    try (WalWriter writer = WalWriter.open(path, Clock.system(), Metrics.NOOP)) {
      for (WalFixtures.Mutation mutation : mutations) {
        writer.append(mutation.payload(), Durability.SYNC);
      }
    }
  }
}
