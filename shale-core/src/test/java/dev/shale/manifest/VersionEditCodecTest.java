package dev.shale.manifest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.shale.CorruptionException;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

/**
 * What the version-edit decoder rejects (format.md §3.1; ADR-0024 §6): each malformed payload below
 * has no checksum problem — the framing would pass it — so only the decoder stands between it and a
 * wrong set of live files.
 */
class VersionEditCodecTest {

  /** A valid key: "a", then the trailer for sequence 2, PUT. */
  private static final String KEY = "09 61 01 02 00 00 00 00 00 00";

  @Test
  void anEmptyPayload_isAnEmptyEdit() {
    assertThat(VersionEditCodec.decode(new byte[0])).isEqualTo(VersionEdit.builder().build());
  }

  @Test
  void anUnknownTag_isCorruption() {
    assertCorrupt("09 05", "unknown version edit tag"); // tag 9: LevelDB's prev log number
  }

  @Test
  void aFieldOverrunningTheRecord_isCorruption() {
    assertCorrupt("01 05 61 62", "overruns the record"); // comparator name claims 5 bytes, has 2
    assertCorrupt("07 00 0C D2 09 01 02 09 61 01", "overruns the record"); // key cut short
    assertCorrupt("02", "truncated varint"); // a tag with no field
  }

  @Test
  void aLevelOutsideZeroToSix_isCorruption() {
    assertCorrupt("06 07 03", "level out of range");
    assertCorrupt("05 FF FF FF FF 0F " + KEY, "level out of range");
  }

  @Test
  void aVarint32FieldOf2To32OrMore_isCorruption() {
    assertCorrupt("06 80 80 80 80 10 03", "varint32 field exceeds 32 bits"); // level 2^32
  }

  @Test
  void aNumberOf2To63OrMore_isCorruption() {
    assertCorrupt("02 80 80 80 80 80 80 80 80 80 01", "exceeds 63 bits"); // log number 2^63
  }

  @Test
  void aScalarTagGivenTwice_isCorruption() {
    assertCorrupt("03 05 03 06", "repeats tag 3");
  }

  @Test
  void anInternalKeyShorterThanItsTrailer_isCorruption() {
    assertCorrupt("05 00 07 61 01 02 00 00 00 00", "shorter than an internal key's trailer");
  }

  @Test
  void anInvertedSequenceRange_isCorruption() {
    assertCorrupt("07 00 0C D2 09 03 02 " + KEY + " " + KEY, "smallest sequence exceeds");
  }

  @Test
  void aComparatorNameThatIsNotUtf8_isCorruption() {
    assertCorrupt("01 02 C3 28", "not valid UTF-8");
  }

  private static void assertCorrupt(String hex, String reason) {
    byte[] payload = HexFormat.of().parseHex(hex.replace(" ", ""));

    assertThatThrownBy(() -> VersionEditCodec.decode(payload))
        .isExactlyInstanceOf(CorruptionException.class)
        .hasMessageContaining(reason);
  }
}
