package dev.shale.manifest;

import static org.assertj.core.api.Assertions.assertThat;

import dev.shale.env.Env;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The manifest golden (on-disk-formats.md §4): it decodes to the edits its {@code .json} describes,
 * the writer reproduces it byte for byte, and the worked example in {@code format.md} §5 is what
 * the encoder writes. The golden and the example were computed by an independent implementation
 * before the encoder existed, so a mistake shared by Shale's writer and reader cannot pass here.
 */
class GoldenManifestTest {

  static final Path GOLDEN = Path.of("src/test/resources/golden/manifest/v2/three-edits.manifest");

  @TempDir private Path dir;

  @Test
  void golden_decodesToItsEdits() throws IOException {
    assertThat(ManifestReader.read(Env.DEFAULT, GOLDEN))
        .containsExactlyElementsOf(ManifestFixtures.THREE_EDITS);
  }

  @Test
  void writer_reproducesTheGoldenByteForByte() throws IOException {
    Path written = dir.resolve("MANIFEST-000001");
    try (ManifestWriter writer = ManifestWriter.create(Env.DEFAULT, written)) {
      for (VersionEdit edit : ManifestFixtures.THREE_EDITS) {
        writer.log(edit);
      }
      assertThat(writer.sizeBytes()).isEqualTo(190);
    }

    assertThat(Files.readAllBytes(written)).isEqualTo(Files.readAllBytes(GOLDEN));
  }

  @Test
  void workedExample_matchesFormatMdSection5() throws IOException {
    assertThat(VersionEditCodec.encode(ManifestFixtures.WORKED_EXAMPLE))
        .isEqualTo(
            hex(
                "07 00 0C D2 09 01 02 09 61 01 02 00 00 00 00 00 00 09 63 01 01 00 00 00 00 00 00"));

    Path written = dir.resolve("MANIFEST-000002");
    try (ManifestWriter writer = ManifestWriter.create(Env.DEFAULT, written)) {
      writer.log(ManifestFixtures.WORKED_EXAMPLE);
    }
    assertThat(Files.readAllBytes(written))
        .isEqualTo(
            hex(
                "4E 41 4D 65 6C 61 68 53 02 00 00 00 00 00 00 00"
                    + " 41 03 B2 E9 1B 00 01 B7 1F A8 42"
                    + " 07 00 0C D2 09 01 02 09 61 01 02 00 00 00 00 00 00"
                    + " 09 63 01 01 00 00 00 00 00 00"));
  }

  private static byte[] hex(String spaced) {
    return HexFormat.of().parseHex(spaced.replace(" ", ""));
  }
}
