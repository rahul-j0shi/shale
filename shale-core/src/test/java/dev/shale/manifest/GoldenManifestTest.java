package dev.shale.manifest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.shale.CorruptionException;
import dev.shale.internal.key.InternalKey;
import dev.shale.internal.key.ValueType;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The current reader must still decode the frozen v1 fixture, and every corruption of it must be
 * caught (on-disk-formats.md §3-4).
 *
 * <p>{@code first-flush.manifest} was written once and is <b>never regenerated to make a test
 * pass</b>. A failure here means the reader changed behaviour or the format drifted — either the
 * bug being hunted, or an intentional change that owes the §3 procedure.
 */
class GoldenManifestTest {

  private static final String FIXTURE = "/golden/manifest/v1/first-flush.manifest";

  @TempDir private Path dir;

  @Test
  void decodesFrozenFirstFlushFixture() throws IOException {
    List<VersionEdit> edits = ManifestReader.readAll(copyFixture("first-flush.manifest"));

    assertThat(edits).hasSize(2);

    VersionEdit opening = edits.get(0);
    assertThat(opening.comparatorName()).contains("shale.BytewiseComparator");
    assertThat(opening.logNumber()).contains(2L);
    assertThat(opening.nextFileNumber()).contains(3L);
    assertThat(opening.lastSequence()).contains(0L);
    assertThat(opening.addedFiles()).isEmpty();

    VersionEdit install = edits.get(1);
    assertThat(install.logNumber()).contains(4L);
    assertThat(install.nextFileNumber()).contains(5L);
    assertThat(install.lastSequence()).contains(2L);
    assertThat(install.addedFiles())
        .containsExactly(new FileMetadata(0, 3, 132, internalKey("a", 1), internalKey("b", 2)));
    assertThat(install.deletedFiles()).isEmpty();
  }

  @Test
  void everyBitFlip_isDetectedOrProvablyHarmless() throws IOException {
    byte[] golden = Files.readAllBytes(copyFixture("source.manifest"));

    for (int index = 0; index < golden.length; index++) {
      for (int bit = 0; bit < 8; bit++) {
        byte[] corrupted = golden.clone();
        corrupted[index] ^= (byte) (1 << bit);
        Path path = dir.resolve("flip-" + index + "-" + bit + ".manifest");
        Files.write(path, corrupted);

        String where = "byte " + index + " bit " + bit;
        try {
          List<VersionEdit> edits = ManifestReader.readAll(path);
          // Not every flip must throw: one inside the *last* record's payload can make it look
          // torn, and a discarded tail is the correct answer to a crash mid-install. What must
          // never happen is a silently wrong decode of a record we accepted.
          assertThat(edits).as("%s: a surviving record must decode to the truth", where).hasSize(1);
          assertThat(edits.get(0).comparatorName())
              .as("%s: first edit intact", where)
              .contains("shale.BytewiseComparator");
        } catch (CorruptionException expected) {
          // Detected — the desired outcome (N4).
        }
      }
    }
  }

  @Test
  void aManifestIsNotAWalSegment() throws IOException {
    Path path = copyFixture("as-wal.manifest");

    assertThatThrownBy(
            () -> dev.shale.wal.WalReader.readAll(path, dev.shale.wal.RecoveryPolicy.STRICT))
        .isInstanceOf(CorruptionException.class)
        .hasMessageContaining("magic");
  }

  private Path copyFixture(String name) throws IOException {
    Path path = dir.resolve(name);
    try (InputStream in = getClass().getResourceAsStream(FIXTURE)) {
      assertThat(in).as("golden fixture on the classpath").isNotNull();
      Files.copy(in, path);
    }
    return path;
  }

  private static byte[] internalKey(String userKey, long sequence) {
    return new InternalKey(userKey.getBytes(StandardCharsets.US_ASCII), sequence, ValueType.PUT)
        .encode();
  }
}
