package dev.shale.wal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.shale.CorruptionException;
import dev.shale.internal.log.LogReadResult;
import dev.shale.internal.log.LogReadResult.Tail;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * A crash leaves a byte-prefix of what was written, so cutting a v2 golden at offset {@code c} is
 * exactly a crash at {@code c}. At every cut the reader must return the records that ended at or
 * before it — no fewer, no more — and name how the file ended, from the golden's own layout: inside
 * a header is a torn header, inside a payload a torn payload, between the fragments of one record
 * an open record, and at a record boundary (or inside the padding after one) a clean end. None of
 * these is corruption: in v2 every length the reader used has passed its checksum.
 *
 * <p>Small goldens are cut at every offset here; large ones near every boundary here, and at every
 * offset in the crash tier.
 */
class LogV2TruncationTest {

  @TempDir private Path dir;

  @ParameterizedTest(name = "{0}")
  @MethodSource("small")
  void everyCut_ofASmallGolden_keepsExactlyTheCompleteRecords(WalGoldens.Golden golden) {
    assertThat(mismatches(golden, IntStream.rangeClosed(16, golden.bytes().length))).isEmpty();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("large")
  void cutsNearEveryBoundary_ofALargeGolden_keepExactlyTheCompleteRecords(
      WalGoldens.Golden golden) {
    int length = golden.bytes().length;
    IntStream boundaries =
        Stream.of(
                IntStream.of(golden.fragmentStarts()),
                IntStream.of(golden.recordEnds()),
                IntStream.of(golden.paddingStarts()),
                IntStream.of(16, length))
            .flatMapToInt(s -> s)
            .flatMap(boundary -> IntStream.rangeClosed(boundary - 12, boundary + 12))
            .filter(cut -> cut >= 16 && cut <= length)
            .distinct();

    assertThat(mismatches(golden, boundaries)).isEmpty();
  }

  @Tag("crash")
  @ParameterizedTest(name = "{0}")
  @MethodSource("large")
  void everyCut_ofALargeGolden_keepsExactlyTheCompleteRecords(WalGoldens.Golden golden) {
    assertThat(mismatches(golden, IntStream.rangeClosed(16, golden.bytes().length))).isEmpty();
  }

  @Test
  void strict_rejectsATornPayload() throws IOException {
    Path segment = cut(WalGoldens.TWO_PUTS, 60); // inside B's payload

    assertThatThrownBy(() -> WalReader.readAll(segment, RecoveryPolicy.STRICT))
        .isInstanceOf(CorruptionException.class)
        .hasMessageContaining("torn");
  }

  @Test
  void truncateTail_acceptsATornPayload() throws IOException {
    Path segment = cut(WalGoldens.TWO_PUTS, 60);

    assertThat(WalReader.readAll(segment, RecoveryPolicy.TRUNCATE_TAIL)).hasSize(1);
  }

  @Test
  void strict_acceptsACutAtARecordBoundary() throws IOException {
    Path segment = cut(WalGoldens.TWO_PUTS, 43); // exactly after A

    assertThat(WalReader.readAll(segment, RecoveryPolicy.STRICT)).hasSize(1);
  }

  static List<WalGoldens.Golden> small() {
    return WalGoldens.SMALL;
  }

  static List<WalGoldens.Golden> large() {
    return WalGoldens.LARGE;
  }

  private Path cut(WalGoldens.Golden golden, int length) throws IOException {
    Path segment = dir.resolve(golden.file());
    Files.write(segment, Arrays.copyOf(golden.bytes(), length));
    return segment;
  }

  /** Reads each prefix of {@code golden} and describes every way it differs from the oracle. */
  private static List<String> mismatches(WalGoldens.Golden golden, IntStream cuts) {
    byte[] data = golden.bytes();
    return cuts.mapToObj(cut -> mismatch(golden, Arrays.copyOf(data, cut), cut))
        .filter(Objects::nonNull)
        .toList();
  }

  private static String mismatch(WalGoldens.Golden golden, byte[] prefix, int cut) {
    LogReadResult result;
    try {
      result = WalGoldens.read(prefix);
    } catch (CorruptionException e) {
      return "cut " + cut + ": " + e.getMessage();
    }
    int complete = (int) IntStream.of(golden.recordEnds()).filter(end -> end <= cut).count();
    List<byte[]> expected =
        golden.mutations().subList(0, complete).stream()
            .map(WalFixtures.Mutation::payload)
            .toList();
    boolean sameRecords =
        result.records().size() == complete
            && IntStream.range(0, complete)
                .allMatch(i -> Arrays.equals(result.records().get(i), expected.get(i)));
    Tail tail = expectedTail(golden, cut);
    int endOffset = complete == 0 ? 16 : golden.recordEnds()[complete - 1];
    if (sameRecords && result.tail() == tail && result.endOffset() == endOffset) {
      return null;
    }
    return "cut %d: %d records (expected %d), tail %s (expected %s), end %d (expected %d)"
        .formatted(
            cut,
            result.records().size(),
            complete,
            result.tail(),
            tail,
            result.endOffset(),
            endOffset);
  }

  private static int blockEnd(int offset) {
    return 16 + ((offset - 16) / 32_768 + 1) * 32_768;
  }

  /** How a file cut at {@code cut} ended, from the golden's layout alone. */
  private static Tail expectedTail(WalGoldens.Golden golden, int cut) {
    boolean atRecordBoundary =
        cut == 16 || IntStream.of(golden.recordEnds()).anyMatch(end -> end == cut);
    // In or just after the padding that follows a record, nothing of the next record exists yet.
    boolean inPaddingAfterRecord =
        IntStream.of(golden.paddingStarts())
            .anyMatch(start -> cut > start && cut <= blockEnd(start));
    return atRecordBoundary || inPaddingAfterRecord ? Tail.CLEAN : tailInsideRecord(golden, cut);
  }

  /** The tail of a cut that is not at a record boundary: inside a fragment, or between two. */
  private static Tail tailInsideRecord(WalGoldens.Golden golden, int cut) {
    for (int i = 0; i < golden.fragmentStarts().length; i++) {
      int start = golden.fragmentStarts()[i];
      int payloadEnd = start + WalGoldens.HEADER + golden.payloadLengths()[i];
      if (cut > start && cut < payloadEnd) {
        return cut < start + WalGoldens.HEADER ? Tail.TORN_HEADER : Tail.TORN_PAYLOAD;
      }
    }
    return Tail.OPEN_RECORD; // at a fragment boundary inside a record that spans blocks
  }
}
