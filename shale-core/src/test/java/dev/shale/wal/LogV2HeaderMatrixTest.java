package dev.shale.wal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.shale.CorruptionException;
import dev.shale.internal.log.LogReadResult;
import dev.shale.internal.log.LogReadResult.Tail;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The review's finite matrix (ADR-0024; M4.5 Step 4), run on every fragment header of every v2
 * golden: each of its 88 single-bit flips, each cut inside it and inside its payload, and a
 * zero-filled tail in its place. Each case names the row of the classification table it must hit
 * ({@code internal/log/format.md} §3), so a regression says which rule broke.
 */
class LogV2HeaderMatrixTest {

  @Test
  void sh18_lengthBitFlip_isAHeaderCrcMismatchNotATornTail() {
    // SH-18: bit 6 of byte 21 turns A's length 16 into 16,400. In v1 the payload then ran past the
    // end of the file and was read as a torn append, so recovery returned no records. In v2 that
    // length is checked before it is believed.
    byte[] data = WalGoldens.TWO_PUTS.bytes();
    data[21] ^= 0x40;

    assertThatThrownBy(() -> WalGoldens.read(data))
        .isExactlyInstanceOf(CorruptionException.class)
        .hasMessageContaining("header CRC mismatch");
  }

  @ParameterizedTest(name = "{0} fragment {1}")
  @MethodSource("fragments")
  void everySingleBitFlip_inAHeader_isAHeaderCrcMismatch(WalGoldens.Golden golden, int fragment) {
    byte[] data = golden.bytes();
    int start = golden.fragmentStarts()[fragment];

    List<Integer> accepted =
        IntStream.range(0, WalGoldens.HEADER * 8)
            .filter(bit -> !headerCrcMismatch(data, start + bit / 8, 1 << (bit % 8)))
            .boxed()
            .toList();

    assertThat(accepted).as("bits (0–87) not rejected as a header CRC mismatch").isEmpty();
  }

  @ParameterizedTest(name = "{0} fragment {1}")
  @MethodSource("fragments")
  void everyCut_insideAHeader_isATornHeader(WalGoldens.Golden golden, int fragment) {
    int start = golden.fragmentStarts()[fragment];

    List<Tail> tails =
        IntStream.range(start + 1, start + WalGoldens.HEADER)
            .mapToObj(cut -> WalGoldens.read(Arrays.copyOf(golden.bytes(), cut)).tail())
            .distinct()
            .toList();

    assertThat(tails).containsExactly(Tail.TORN_HEADER);
  }

  @ParameterizedTest(name = "{0} fragment {1}")
  @MethodSource("fragments")
  void cutsInsideAPayload_areATornPayload(WalGoldens.Golden golden, int fragment) {
    int payloadStart = golden.fragmentStarts()[fragment] + WalGoldens.HEADER;
    int payloadEnd = payloadStart + golden.payloadLengths()[fragment];

    List<Tail> tails =
        IntStream.of(
                payloadStart, payloadStart + 1, (payloadStart + payloadEnd) / 2, payloadEnd - 1)
            .mapToObj(cut -> WalGoldens.read(Arrays.copyOf(golden.bytes(), cut)).tail())
            .distinct()
            .toList();

    assertThat(tails).containsExactly(Tail.TORN_PAYLOAD);
  }

  @ParameterizedTest(name = "{0} fragment {1}")
  @MethodSource("fragments")
  void zeroFilledTail_inPlaceOfAFragment_isUnwritten(WalGoldens.Golden golden, int fragment) {
    int start = golden.fragmentStarts()[fragment];
    int completeBefore =
        (int) IntStream.of(golden.recordEnds()).filter(end -> end <= start).count();

    List<LogReadResult> results =
        IntStream.of(WalGoldens.HEADER, 100, 40_000)
            .mapToObj(
                zeros ->
                    WalGoldens.read(
                        Arrays.copyOf(Arrays.copyOf(golden.bytes(), start), start + zeros)))
            .toList();

    assertThat(results).extracting(LogReadResult::tail).containsOnly(Tail.UNWRITTEN);
    assertThat(results).extracting(result -> result.records().size()).containsOnly(completeBefore);
  }

  static List<Arguments> fragments() {
    return WalGoldens.ALL.stream()
        .flatMap(
            golden ->
                IntStream.range(0, golden.fragmentStarts().length)
                    .mapToObj(i -> Arguments.of(golden, i)))
        .toList();
  }

  private static boolean headerCrcMismatch(byte[] data, int offset, int mask) {
    data[offset] ^= (byte) mask;
    try {
      WalGoldens.read(data);
      return false;
    } catch (CorruptionException e) {
      return e.getMessage().contains("header CRC mismatch");
    } finally {
      data[offset] ^= (byte) mask;
    }
  }
}
