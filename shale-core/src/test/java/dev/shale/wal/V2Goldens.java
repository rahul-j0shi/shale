package dev.shale.wal;

import dev.shale.internal.log.LogReadResult;
import dev.shale.internal.log.LogReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

/**
 * The v2 golden WAL fixtures with their layout, for the framing matrices. The layout constants are
 * copied from each fixture's {@code .json}, which an implementation independent of {@code
 * LogReader} verified; they are deliberately not derived from the reader under test.
 */
final class V2Goldens {

  /** Bytes in a v2 fragment header. */
  static final int HEADER = 11;

  /**
   * One golden fixture and where its parts lie.
   *
   * @param file the fixture's name under {@code golden/wal/v2/}
   * @param mutations its logical contents
   * @param fragmentStarts the offset of each fragment header
   * @param payloadLengths the payload length of each fragment
   * @param recordEnds the offset just past each complete record
   * @param paddingStarts where each run of block padding starts (it runs to the block's end)
   */
  record Golden(
      String file,
      List<WalFixtures.Mutation> mutations,
      int[] fragmentStarts,
      int[] payloadLengths,
      int[] recordEnds,
      int[] paddingStarts) {

    byte[] bytes() {
      try {
        return Files.readAllBytes(Path.of("src/test/resources/golden/wal/v2/" + file));
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }

    /** Every byte offset that lies inside some fragment header. */
    IntStream headerOffsets() {
      return Arrays.stream(fragmentStarts).flatMap(start -> IntStream.range(start, start + HEADER));
    }

    @Override
    public String toString() {
      return file;
    }
  }

  static final Golden SINGLE_PUT =
      new Golden(
          "single-put.wal",
          WalFixtures.SINGLE_PUT,
          new int[] {16},
          new int[] {14},
          new int[] {41},
          new int[0]);

  static final Golden TWO_PUTS =
      new Golden(
          "two-puts.wal",
          WalFixtures.TWO_PUTS,
          new int[] {16, 43},
          new int[] {16, 16},
          new int[] {43, 70},
          new int[0]);

  static final Golden FRAGMENTED =
      new Golden(
          "fragmented.wal",
          WalFixtures.FRAGMENTED,
          new int[] {16, 32_784, 65_552, 80_064},
          new int[] {32_757, 32_757, 14_501, 16},
          new int[] {80_064, 80_091},
          new int[0]);

  static final Golden BLOCK_PADDING =
      new Golden(
          "block-padding.wal",
          WalFixtures.BLOCK_PADDING_V2,
          new int[] {16, 32_784, 65_552},
          new int[] {32_754, 32_747, 16},
          new int[] {32_781, 65_542, 65_579},
          new int[] {32_781, 65_542});

  static final List<Golden> ALL = List.of(SINGLE_PUT, TWO_PUTS, FRAGMENTED, BLOCK_PADDING);

  /** The goldens small enough to mutate at every byte in the fast tier. */
  static final List<Golden> SMALL = List.of(SINGLE_PUT, TWO_PUTS);

  /** The goldens whose every-byte sweeps run in the crash tier. */
  static final List<Golden> LARGE = List.of(FRAGMENTED, BLOCK_PADDING);

  private V2Goldens() {}

  /** Reads {@code data} as a WAL segment, through the framing alone (no recovery policy). */
  static LogReadResult read(byte[] data) {
    return LogReader.read(data, WalFormat.MAGIC, WalFormat.READABLE_VERSIONS, "WAL");
  }
}
