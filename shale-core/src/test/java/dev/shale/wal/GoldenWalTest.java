package dev.shale.wal;

import static org.assertj.core.api.Assertions.assertThat;

import dev.shale.internal.key.InternalKey;
import dev.shale.internal.key.ValueType;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Golden-file test (on-disk-formats.md §4): a committed WAL segment written by format v1 is decoded
 * and its logical contents asserted against the sibling {@code .json}. The fixture is frozen —
 * never regenerated to make a test pass; a failure here means the reader changed behaviour or the
 * format drifted, which is either the bug you are hunting or an intentional change that must follow
 * the §3 procedure.
 */
class GoldenWalTest {

  private static final Path GOLDEN = Path.of("src/test/resources/golden/wal/v1/single-put.wal");

  @Test
  void golden_singlePut_decodesToExpectedMutation() throws IOException {
    List<byte[]> records = WalReader.readAll(GOLDEN, RecoveryPolicy.STRICT);

    assertThat(records).hasSize(1);
    WalRecordCodec.Decoded decoded = WalRecordCodec.decode(records.get(0));
    assertThat(decoded.internalKey()).isEqualTo(new InternalKey(ascii("key"), 1, ValueType.PUT));
    assertThat(decoded.value()).isEqualTo(ascii("v"));
  }

  /**
   * The v1 fixtures written by the M4 writer before M4.5 changed the framing, and the v2 fixtures
   * written by the M4.5 writer. Each must decode to exactly its {@link WalFixtures} definition:
   * every record, in order, with nothing torn (they are read {@code STRICT}). The v1 ones are what
   * keeps segments written before M4.5 readable.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("fixtures")
  void golden_fixture_decodesToItsDefinition(String file, List<WalFixtures.Mutation> expected)
      throws IOException {
    List<WalRecordCodec.Decoded> decoded =
        WalReader.readAll(Path.of("src/test/resources/golden/wal/" + file), RecoveryPolicy.STRICT)
            .stream()
            .map(WalRecordCodec::decode)
            .toList();

    assertThat(decoded)
        .as("%s keys, in order", file)
        .extracting(WalRecordCodec.Decoded::internalKey)
        .containsExactlyElementsOf(
            expected.stream().map(WalFixtures.Mutation::internalKey).toList());
    assertThat(decoded)
        .as("%s values, in order", file)
        .extracting(WalRecordCodec.Decoded::value)
        .usingElementComparator(Arrays::compare)
        .containsExactlyElementsOf(expected.stream().map(WalFixtures.Mutation::value).toList());
  }

  static List<Arguments> fixtures() {
    return List.of(
        Arguments.of("v1/two-puts.wal", WalFixtures.TWO_PUTS),
        Arguments.of("v1/fragmented.wal", WalFixtures.FRAGMENTED),
        Arguments.of("v1/block-padding.wal", WalFixtures.BLOCK_PADDING),
        Arguments.of("v2/single-put.wal", WalFixtures.SINGLE_PUT),
        Arguments.of("v2/two-puts.wal", WalFixtures.TWO_PUTS),
        Arguments.of("v2/fragmented.wal", WalFixtures.FRAGMENTED),
        Arguments.of("v2/block-padding.wal", WalFixtures.BLOCK_PADDING_V2));
  }

  private static byte[] ascii(String value) {
    return value.getBytes(StandardCharsets.US_ASCII);
  }
}
