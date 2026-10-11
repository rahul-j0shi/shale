package dev.shale.manifest;

import dev.shale.env.Env;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Writes {@link ManifestFixtures#THREE_EDITS} with the current {@link ManifestWriter}, to compare
 * with the committed golden, which an independent implementation wrote first. Run by hand from
 * {@code shale-core} after {@code ./gradlew :shale-core:testClasses}:
 *
 * <pre>{@code
 * java -cp build/classes/java/main:build/classes/java/test \
 *     dev.shale.manifest.ManifestFixtureGenerator <output-file>
 * }</pre>
 *
 * The committed golden is never regenerated (on-disk-formats.md §4).
 */
final class ManifestFixtureGenerator {

  private ManifestFixtureGenerator() {}

  public static void main(String[] args) throws IOException {
    Path out = Path.of(args[0]);
    Files.deleteIfExists(out);
    try (ManifestWriter writer = ManifestWriter.create(Env.DEFAULT, out)) {
      for (VersionEdit edit : ManifestFixtures.THREE_EDITS) {
        writer.log(edit);
      }
    }
  }
}
