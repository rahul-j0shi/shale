package dev.shale.manifest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.shale.CorruptionException;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Damage to a manifest is never silent. Log framing v2 leaves no byte unchecked — the file header's
 * fields are compared, each fragment header carries a CRC, each payload another — so flipping any
 * single bit of the golden must be {@link CorruptionException}, never a quietly different list of
 * edits. And a cut anywhere keeps exactly the edits that end before it: a torn final record was
 * never synced, so it never took effect (format.md §4).
 */
class ManifestBitFlipTest {

  private static final int[] RECORD_ENDS = {86, 130, 190};

  @Test
  void everyBitOfTheGolden_isCorruption() throws IOException {
    byte[] golden = Files.readAllBytes(GoldenManifestTest.GOLDEN);
    for (int bit = 0; bit < golden.length * 8; bit++) {
      byte[] damaged = golden.clone();
      damaged[bit / 8] ^= (byte) (1 << (bit % 8));
      int flipped = bit;

      assertThatThrownBy(() -> ManifestReader.decode(damaged))
          .as("bit %d (byte %d)", flipped, flipped / 8)
          .isInstanceOf(CorruptionException.class);
    }
  }

  @Test
  void everyCut_keepsExactlyTheEditsEndingBeforeIt() throws IOException {
    byte[] golden = Files.readAllBytes(GoldenManifestTest.GOLDEN);
    for (int cut = 16; cut <= golden.length; cut++) {
      int complete = 0;
      while (complete < RECORD_ENDS.length && RECORD_ENDS[complete] <= cut) {
        complete++;
      }
      List<VersionEdit> expected = ManifestFixtures.THREE_EDITS.subList(0, complete);

      assertThat(ManifestReader.decode(Arrays.copyOf(golden, cut)))
          .as("cut at %d", cut)
          .containsExactlyElementsOf(expected);
    }
  }

  @Test
  void aFileShorterThanItsHeader_isCorruption() throws IOException {
    byte[] golden = Files.readAllBytes(GoldenManifestTest.GOLDEN);
    for (int cut = 0; cut < 16; cut++) {
      byte[] shortFile = Arrays.copyOf(golden, cut);

      assertThatThrownBy(() -> ManifestReader.decode(shortFile))
          .as("cut at %d", cut)
          .isInstanceOf(CorruptionException.class)
          .hasMessageContaining("shorter than its file header");
    }
  }
}
