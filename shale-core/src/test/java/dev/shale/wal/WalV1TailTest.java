package dev.shale.wal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import dev.shale.CorruptionException;
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
 * Segments written before M4.5 are v1, whose fragment header has no checksum. ADR-0024 §3: a
 * complete v1 header whose payload runs past the end of the file is <em>unverifiable</em> — v1
 * cannot tell a torn append from a damaged length — so it is refused under every policy rather than
 * dropped, and the message tells an operator exactly what accepting the loss would mean. That
 * refusal has a cost, asserted here rather than hidden: a genuine crash mid-payload in a v1 segment
 * no longer opens on its own.
 */
class WalV1TailTest {

  @TempDir private Path dir;

  @Test
  void sh18_lengthBitFlip_isUnverifiableUnderBothPolicies() throws IOException {
    // SH-18: bit 6 of byte 21 turns A's length 16 into 16,400; the payload then runs past the end
    // of the 62-byte file. M4 read that as a torn append and recovered nothing, silently.
    Path segment = v1Golden(WalGoldens.V1_TWO_PUTS);
    flipBit(segment, 21, 6);

    assertThatThrownBy(() -> WalReader.readAll(segment, RecoveryPolicy.TRUNCATE_TAIL))
        .isExactlyInstanceOf(CorruptionException.class)
        .hasMessageContaining("unverifiable WAL tail in two-puts.wal at offset 16")
        .hasMessageContaining("header claims 16400 payload bytes, 39 present")
        .hasMessageContaining("truncate the file to 16 bytes");
    assertThatThrownBy(() -> WalReader.readAll(segment, RecoveryPolicy.STRICT))
        .isExactlyInstanceOf(CorruptionException.class)
        .hasMessageContaining("unverifiable");
  }

  @Test
  void availabilityCost_aGenuineOneByteTruncationIsAlsoUnverifiable() throws IOException {
    // The review's control: cutting the last byte of B is a real torn append, and M4 recovered A.
    // v1 cannot prove that, so it is refused too. This is the price of not trusting v1 (ADR-0024).
    Path segment = v1Golden(WalGoldens.V1_TWO_PUTS);
    truncate(segment, 61);

    assertThatThrownBy(() -> WalReader.readAll(segment, RecoveryPolicy.TRUNCATE_TAIL))
        .isExactlyInstanceOf(CorruptionException.class)
        .hasMessageContaining("at offset 39")
        .hasMessageContaining("header claims 16 payload bytes, 15 present")
        .hasMessageContaining("truncate the file to 39 bytes");
  }

  @Test
  void aTornV1Header_isStillATornTail() throws IOException {
    // Fewer than 7 bytes of B's header: nothing unverified decides this, so it stays torn.
    Path segment = v1Golden(WalGoldens.V1_TWO_PUTS);
    truncate(segment, 42);

    assertThat(WalReader.readAll(segment, RecoveryPolicy.TRUNCATE_TAIL)).hasSize(1);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("v1Goldens")
  void cutsNearEveryBoundary_keepTheCompleteRecordsOrRefuseAsUnverifiable(
      WalGoldens.Golden golden) {
    int length = golden.bytes().length;
    IntStream cuts =
        Stream.of(
                IntStream.of(golden.fragmentStarts()),
                IntStream.of(golden.recordEnds()),
                IntStream.of(16, length))
            .flatMapToInt(s -> s)
            .flatMap(boundary -> IntStream.rangeClosed(boundary - 12, boundary + 12))
            .filter(cut -> cut >= 16 && cut <= length)
            .distinct();

    assertThat(silentLosses(golden, cuts)).isEmpty();
  }

  @Test
  void everyCut_ofTwoPuts_keepsTheCompleteRecordsOrRefusesAsUnverifiable() {
    WalGoldens.Golden golden = WalGoldens.V1_TWO_PUTS;

    assertThat(silentLosses(golden, IntStream.rangeClosed(16, 62))).isEmpty();
  }

  @Test
  void everyBit_ofTwoPuts_isCorruption() {
    assertThat(undetected(WalGoldens.V1_TWO_PUTS, 8)).isEmpty();
  }

  @Tag("crash")
  @ParameterizedTest(name = "{0}")
  @MethodSource("v1Goldens")
  void everyByte_ofEachV1Golden_isCorruption(WalGoldens.Golden golden) {
    assertThat(undetected(golden, 1)).isEmpty();
  }

  static List<WalGoldens.Golden> v1Goldens() {
    return WalGoldens.V1_ALL;
  }

  /**
   * At each cut: a cut inside a fragment's payload must be refused as unverifiable; any other cut
   * must return exactly the records that ended at or before it. Returns every cut that did neither
   * — above all, one that returned fewer records without an error.
   */
  private static List<String> silentLosses(WalGoldens.Golden golden, IntStream cuts) {
    byte[] data = golden.bytes();
    return cuts.mapToObj(cut -> silentLoss(golden, Arrays.copyOf(data, cut), cut))
        .filter(Objects::nonNull)
        .toList();
  }

  private static String silentLoss(WalGoldens.Golden golden, byte[] prefix, int cut) {
    boolean insidePayload =
        IntStream.range(0, golden.fragmentStarts().length)
            .anyMatch(
                i -> {
                  int payloadStart = golden.fragmentStarts()[i] + golden.headerSize();
                  return cut >= payloadStart && cut < payloadStart + golden.payloadLengths()[i];
                });
    Throwable thrown = catchThrowable(() -> WalReader.read(golden.file(), prefix));
    if (insidePayload) {
      boolean refused =
          thrown instanceof CorruptionException e && e.getMessage().contains("unverifiable");
      return refused ? null : "cut " + cut + ": inside a payload but not refused: " + thrown;
    }
    long complete = IntStream.of(golden.recordEnds()).filter(end -> end <= cut).count();
    int returned = thrown == null ? WalReader.read(golden.file(), prefix).records().size() : -1;
    return returned == complete
        ? null
        : "cut " + cut + ": " + returned + " records (expected " + complete + "), " + thrown;
  }

  /** Every offset XOR each of the first {@code bits} single-bit masks; lists flips not detected. */
  private static List<String> undetected(WalGoldens.Golden golden, int bits) {
    byte[] data = golden.bytes();
    return IntStream.range(0, data.length * bits)
        .mapToObj(
            n -> {
              int offset = n / bits;
              int mask = 1 << (n % bits);
              data[offset] ^= (byte) mask;
              Throwable thrown = catchThrowable(() -> WalReader.read(golden.file(), data));
              data[offset] ^= (byte) mask;
              return thrown != null && thrown.getClass() == CorruptionException.class
                  ? null
                  : "offset "
                      + offset
                      + " bit "
                      + Integer.numberOfTrailingZeros(mask)
                      + ": "
                      + thrown;
            })
        .filter(Objects::nonNull)
        .toList();
  }

  private Path v1Golden(WalGoldens.Golden golden) throws IOException {
    Path segment = dir.resolve(golden.file());
    Files.copy(golden.path(), segment);
    return segment;
  }

  private static void flipBit(Path path, int offset, int bit) throws IOException {
    byte[] data = Files.readAllBytes(path);
    data[offset] ^= (byte) (1 << bit);
    Files.write(path, data);
  }

  private static void truncate(Path path, int length) throws IOException {
    Files.write(path, Arrays.copyOf(Files.readAllBytes(path), length));
  }
}
