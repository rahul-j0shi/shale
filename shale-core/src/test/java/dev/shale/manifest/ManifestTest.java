package dev.shale.manifest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.shale.CorruptionException;
import dev.shale.internal.key.InternalKey;
import dev.shale.internal.key.ValueType;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The manifest file: edits appended over the shared block-log framing (format.md). */
class ManifestTest {

  @TempDir private Path dir;

  @Test
  void editsRoundTripInAppendOrder() throws IOException {
    Path path = dir.resolve("000001.manifest");
    try (ManifestWriter writer = ManifestWriter.open(path)) {
      writer.append(new VersionEdit.Builder().comparatorName("shale.BytewiseComparator").build());
      writer.append(new VersionEdit.Builder().nextFileNumber(7).build());
      writer.append(
          new VersionEdit.Builder()
              .addFile(new FileMetadata(0, 7, 1024, key("a", 1), key("z", 2)))
              .build());
    }

    List<VersionEdit> edits = ManifestReader.readAll(path);

    assertThat(edits).hasSize(3);
    assertThat(edits.get(0).comparatorName()).contains("shale.BytewiseComparator");
    assertThat(edits.get(1).nextFileNumber()).contains(7L);
    assertThat(edits.get(2).addedFiles()).hasSize(1);
    assertThat(edits.get(2).addedFiles().get(0).fileNumber()).isEqualTo(7L);
  }

  @Test
  void anEditLargerThanABlock_spansFragmentsAndReassembles() throws IOException {
    // Spanning *three* blocks, not two: a record that only spans two produces FIRST and LAST
    // and would leave the MIDDLE branch of the assembler untested.
    VersionEdit.Builder builder = new VersionEdit.Builder();
    for (int i = 0; i < 2000; i++) {
      builder.addFile(new FileMetadata(0, i, i, key("key" + i, i), key("key" + i, i)));
    }
    VersionEdit big = builder.build();
    assertThat(big.encode().length).isGreaterThan(2 * 32768);

    Path path = dir.resolve("000002.manifest");
    try (ManifestWriter writer = ManifestWriter.open(path)) {
      writer.append(big);
    }

    List<VersionEdit> edits = ManifestReader.readAll(path);

    assertThat(edits).hasSize(1);
    assertThat(edits.get(0).addedFiles()).isEqualTo(big.addedFiles());
  }

  @Test
  void aWalSegmentIsNotAManifest() throws IOException {
    // The magic is what stops a file being parsed by the wrong reader (format.md §1).
    Path path = dir.resolve("000003.manifest");
    Files.write(path, walLikeHeader());

    assertThatThrownBy(() -> ManifestReader.readAll(path))
        .isInstanceOf(CorruptionException.class)
        .hasMessageContaining("magic");
  }

  @Test
  void aTornEditAtTheTail_isDiscardedBecauseItsInstallNeverCompleted() throws IOException {
    Path path = dir.resolve("000004.manifest");
    try (ManifestWriter writer = ManifestWriter.open(path)) {
      writer.append(new VersionEdit.Builder().nextFileNumber(1).build());
      writer.append(new VersionEdit.Builder().nextFileNumber(2).build());
    }
    byte[] full = Files.readAllBytes(path);

    // Truncating anywhere inside the second edit must leave exactly the first: the second
    // install never finished, so it was never acknowledged and must not be observed.
    for (int length = full.length - 1; length > full.length - 4; length--) {
      Path torn = dir.resolve("torn" + length + ".manifest");
      Files.write(torn, Arrays.copyOf(full, length));

      assertThat(ManifestReader.readAll(torn))
          .as("truncated to %d of %d bytes", length, full.length)
          .hasSize(1);
    }
  }

  @Test
  void aCorruptEditInTheMiddle_isNeverSkipped() throws IOException {
    Path path = dir.resolve("000005.manifest");
    try (ManifestWriter writer = ManifestWriter.open(path)) {
      writer.append(new VersionEdit.Builder().nextFileNumber(1).build());
      writer.append(new VersionEdit.Builder().nextFileNumber(2).build());
    }
    byte[] data = Files.readAllBytes(path);
    data[data.length - 1] ^= 0x01; // flip a bit in the last edit's payload

    Path corrupt = dir.resolve("corrupt.manifest");
    Files.write(corrupt, data);

    assertThatThrownBy(() -> ManifestReader.readAll(corrupt))
        .isInstanceOf(CorruptionException.class)
        .hasMessageContaining("CRC");
  }

  private static byte[] walLikeHeader() {
    byte[] header = new byte[16];
    long walMagic = 0x5368616C6557414CL;
    for (int i = 0; i < 8; i++) {
      header[i] = (byte) (walMagic >>> (8 * i));
    }
    header[8] = 1;
    return header;
  }

  private static byte[] key(String userKey, long sequence) {
    return new InternalKey(userKey.getBytes(StandardCharsets.US_ASCII), sequence, ValueType.PUT)
        .encode();
  }
}
