package dev.shale.manifest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.shale.CorruptionException;
import dev.shale.internal.key.InternalKey;
import dev.shale.internal.key.ValueType;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The tagged VersionEdit payload of {@code manifest/format.md} §4. */
class VersionEditTest {

  @Test
  void emptyEdit_roundTripsToAnEditThatChangesNothing() {
    VersionEdit decoded = VersionEdit.decode(new VersionEdit.Builder().build().encode());

    assertThat(decoded.comparatorName()).isEmpty();
    assertThat(decoded.logNumber()).isEmpty();
    assertThat(decoded.nextFileNumber()).isEmpty();
    assertThat(decoded.lastSequence()).isEmpty();
    assertThat(decoded.addedFiles()).isEmpty();
    assertThat(decoded.deletedFiles()).isEmpty();
  }

  @Test
  void scalarFields_roundTrip() {
    VersionEdit edit =
        new VersionEdit.Builder()
            .comparatorName("shale.BytewiseComparator")
            .logNumber(2)
            .nextFileNumber(3)
            .lastSequence(4021)
            .build();

    VersionEdit decoded = VersionEdit.decode(edit.encode());

    assertThat(decoded.comparatorName()).contains("shale.BytewiseComparator");
    assertThat(decoded.logNumber()).contains(2L);
    assertThat(decoded.nextFileNumber()).contains(3L);
    assertThat(decoded.lastSequence()).contains(4021L);
  }

  @Test
  void addedAndDeletedFiles_accumulateInOrder() {
    VersionEdit edit =
        new VersionEdit.Builder()
            .addFile(new FileMetadata(0, 7, 4096, key("a", 1), key("m", 9)))
            .addFile(new FileMetadata(0, 8, 8192, key("n", 10), key("z", 20)))
            .deleteFile(0, 3)
            .deleteFile(0, 4)
            .build();

    VersionEdit decoded = VersionEdit.decode(edit.encode());

    assertThat(decoded.addedFiles()).extracting(FileMetadata::fileNumber).containsExactly(7L, 8L);
    assertThat(decoded.addedFiles().get(0).sizeBytes()).isEqualTo(4096);
    assertThat(decoded.addedFiles().get(0).smallest()).isEqualTo(key("a", 1));
    assertThat(decoded.addedFiles().get(1).largest()).isEqualTo(key("z", 20));
    assertThat(decoded.deletedFiles())
        .containsExactly(new DeletedFile(0, 3), new DeletedFile(0, 4));
  }

  @Test
  void firstManifestEdit_matchesTheWorkedExampleInFormatMd() {
    // format.md §6: the 32-byte payload a fresh database's first edit produces. If this
    // changes, the format changed — bump the version and follow on-disk-formats.md §3.
    byte[] encoded =
        new VersionEdit.Builder()
            .comparatorName("shale.BytewiseComparator")
            .logNumber(2)
            .nextFileNumber(3)
            .lastSequence(0)
            .build()
            .encode();

    assertThat(encoded).hasSize(32);
    assertThat(hex(encoded))
        .isEqualTo(
            "01 18 73 68 61 6C 65 2E 42 79 74 65 77 69 73 65"
                + " 43 6F 6D 70 61 72 61 74 6F 72 02 02 03 03 04 00");
  }

  @Test
  void unknownTag_isCorruptionNotASkippedField() {
    // N4: a manifest says which files exist, so a field we cannot read may not be stepped over.
    byte[] payload = {(byte) 99, 0x01};

    assertThatThrownBy(() -> VersionEdit.decode(payload))
        .isInstanceOf(CorruptionException.class)
        .hasMessageContaining("unknown version edit tag")
        .hasMessageContaining("offset=0");
  }

  @Test
  void truncatedField_isCorruption() {
    byte[] full =
        new VersionEdit.Builder().comparatorName("shale.BytewiseComparator").build().encode();

    for (int length = 1; length < full.length; length++) {
      byte[] truncated = Arrays.copyOf(full, length);
      assertThatThrownBy(() -> VersionEdit.decode(truncated))
          .as("payload truncated to %d of %d bytes", length, full.length)
          .isInstanceOf(CorruptionException.class);
    }
  }

  @Test
  void trailingGarbageAfterAValidField_isCorruption() {
    byte[] valid = new VersionEdit.Builder().logNumber(2).build().encode();
    byte[] withGarbage = Arrays.copyOf(valid, valid.length + 1);
    withGarbage[valid.length] = 0x00; // tag 0 is not assigned

    assertThatThrownBy(() -> VersionEdit.decode(withGarbage))
        .isInstanceOf(CorruptionException.class);
  }

  private static byte[] key(String userKey, long sequence) {
    return new InternalKey(userKey.getBytes(StandardCharsets.US_ASCII), sequence, ValueType.PUT)
        .encode();
  }

  private static String hex(byte[] bytes) {
    List<String> parts = new java.util.ArrayList<>();
    for (byte b : bytes) {
      parts.add(String.format("%02X", b));
    }
    return String.join(" ", parts);
  }
}
