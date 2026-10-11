package dev.shale.wal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import dev.shale.CorruptionException;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * In log framing v2 every byte of a file is covered by some check: the file header by its magic,
 * version and reserved field, each fragment header by {@code headerCrc}, each payload by {@code
 * payloadCrc}, padding by being zero. So a single flipped bit anywhere must be {@link
 * CorruptionException} — never a shorter log, never a "torn tail", and never another exception
 * type, which would be a reader bug (on-disk-formats.md §3 step 7; ADR-0024).
 *
 * <p>The small goldens are swept at every bit in the fast tier; the two large ones (65 and 80 KB)
 * at every byte in the crash tier, and at every bit of every header and padding byte here.
 */
class LogV2BitFlipTest {

  @ParameterizedTest(name = "{0}")
  @MethodSource("all")
  void everyBit_ofEveryHeaderAndPaddingByte_isCorruption(V2Goldens.Golden golden) {
    byte[] data = golden.bytes();
    IntStream structural =
        IntStream.concat(
            IntStream.range(0, 16), // file header
            IntStream.concat(golden.headerOffsets(), paddingOffsets(golden)));

    assertThat(undetectedBitFlips(data, structural.toArray())).isEmpty();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("small")
  void everyBit_ofASmallGolden_isCorruption(V2Goldens.Golden golden) {
    byte[] data = golden.bytes();

    assertThat(undetectedBitFlips(data, IntStream.range(0, data.length).toArray())).isEmpty();
  }

  @Tag("crash")
  @ParameterizedTest(name = "{0}")
  @MethodSource("large")
  void everyByte_ofALargeGolden_isCorruption(V2Goldens.Golden golden) {
    byte[] data = golden.bytes();
    int[] everyOffset = IntStream.range(0, data.length).toArray();

    assertThat(undetected(data, everyOffset, 0x01)).as("XOR 0x01").isEmpty();
    assertThat(undetected(data, everyOffset, 0xFF)).as("XOR 0xFF").isEmpty();
  }

  static List<V2Goldens.Golden> all() {
    return V2Goldens.ALL;
  }

  static List<V2Goldens.Golden> small() {
    return V2Goldens.SMALL;
  }

  static List<V2Goldens.Golden> large() {
    return V2Goldens.LARGE;
  }

  /** Every bit at each of {@code offsets}; the result names each flip that was not detected. */
  private static List<String> undetectedBitFlips(byte[] data, int[] offsets) {
    return IntStream.range(0, 8)
        .boxed()
        .flatMap(bit -> undetected(data, offsets, 1 << bit).stream())
        .toList();
  }

  /**
   * XORs {@code mask} into each offset in turn (restoring it after), reads the file, and reports
   * every case that did not throw exactly {@link CorruptionException}.
   */
  private static List<String> undetected(byte[] data, int[] offsets, int mask) {
    return IntStream.of(offsets)
        .mapToObj(
            offset -> {
              data[offset] ^= (byte) mask;
              Throwable thrown = catchThrowable(() -> V2Goldens.read(data));
              data[offset] ^= (byte) mask;
              String where = "offset " + offset + " ^ 0x" + Integer.toHexString(mask) + ": ";
              if (thrown == null) {
                return where + "accepted";
              }
              return thrown.getClass() == CorruptionException.class ? null : where + thrown;
            })
        .filter(failure -> failure != null)
        .toList();
  }

  /** Every byte of every padding run: from its start to the end of its block. */
  private static IntStream paddingOffsets(V2Goldens.Golden golden) {
    return IntStream.of(golden.paddingStarts())
        .flatMap(start -> IntStream.range(start, blockEnd(start)));
  }

  private static int blockEnd(int offset) {
    int block = (offset - 16) / 32_768;
    return 16 + (block + 1) * 32_768;
  }
}
