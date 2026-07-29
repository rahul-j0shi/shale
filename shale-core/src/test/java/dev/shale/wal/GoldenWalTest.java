package dev.shale.wal;

import static org.assertj.core.api.Assertions.assertThat;

import dev.shale.internal.key.InternalKey;
import dev.shale.internal.key.ValueType;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

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

  private static byte[] ascii(String value) {
    return value.getBytes(StandardCharsets.US_ASCII);
  }
}
