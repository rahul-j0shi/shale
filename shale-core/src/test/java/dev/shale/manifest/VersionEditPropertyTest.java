package dev.shale.manifest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.LongRange;
import net.jqwik.api.constraints.Size;

/** Encode/decode round-trip for arbitrary valid edits (on-disk-formats.md §3, step 6). */
class VersionEditPropertyTest {

  @Property
  void anyEdit_roundTripsThroughItsPayload(
      @ForAll @LongRange(min = 0, max = Integer.MAX_VALUE) long logNumber,
      @ForAll @LongRange(min = 0, max = Integer.MAX_VALUE) long nextFileNumber,
      @ForAll @LongRange(min = 0, max = (1L << 56) - 1) long lastSequence,
      @ForAll @Size(max = 6) List<@Size(max = 32) byte[]> keys) {
    VersionEdit.Builder builder =
        new VersionEdit.Builder()
            .comparatorName("shale.BytewiseComparator")
            .logNumber(logNumber)
            .nextFileNumber(nextFileNumber)
            .lastSequence(lastSequence);
    for (int i = 0; i < keys.size(); i++) {
      builder.addFile(new FileMetadata(0, i, i * 1024L, keys.get(i), keys.get(i)));
      builder.deleteFile(0, i + 100);
    }
    VersionEdit edit = builder.build();

    VersionEdit decoded = VersionEdit.decode(edit.encode());

    assertThat(decoded.comparatorName()).contains("shale.BytewiseComparator");
    assertThat(decoded.logNumber()).contains(logNumber);
    assertThat(decoded.nextFileNumber()).contains(nextFileNumber);
    assertThat(decoded.lastSequence()).contains(lastSequence);
    assertThat(decoded.addedFiles()).isEqualTo(edit.addedFiles());
    assertThat(decoded.deletedFiles()).isEqualTo(edit.deletedFiles());
  }
}
