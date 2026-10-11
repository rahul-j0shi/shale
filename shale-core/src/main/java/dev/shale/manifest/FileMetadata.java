package dev.shale.manifest;

import dev.shale.internal.annotations.Immutable;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;

/**
 * One table in a {@link Version}: where it sits, how big it is, and which keys and sequences it
 * holds — what tag 7 of a version edit records ({@code format.md} §3). The keys are encoded
 * internal keys.
 *
 * <p>The sequence range is RocksDB's addition to LevelDB's {@code FileMetaData}: once compaction
 * writes new files (M6), a file's number no longer says how new its data is, so level-0 files are
 * ordered by their largest sequence.
 *
 * <p>Equality compares the keys' contents, not the arrays' identities.
 *
 * @param level the level, 0 to 6
 * @param number the file number: the table is {@code NNNNNN.sst}
 * @param sizeBytes the file's size
 * @param smallestSequence the smallest sequence number in the table
 * @param largestSequence the largest sequence number in the table
 * @param smallestKey the smallest internal key in the table
 * @param largestKey the largest internal key in the table
 * @see <a href="https://github.com/google/leveldb/blob/main/db/version_edit.h">LevelDB
 *     version_edit.h</a>
 */
@Immutable
public record FileMetadata(
    int level,
    long number,
    long sizeBytes,
    long smallestSequence,
    long largestSequence,
    byte[] smallestKey,
    byte[] largestKey) {

  /** Rejects a level outside 0–6, a negative number or size, and an inverted sequence range. */
  public FileMetadata {
    if (level < 0 || level >= ManifestFormat.NUM_LEVELS) {
      throw new IllegalArgumentException("level out of range: " + level);
    }
    if (number < 0 || sizeBytes < 0 || smallestSequence < 0) {
      throw new IllegalArgumentException("negative number, size or sequence");
    }
    if (smallestSequence > largestSequence) {
      throw new IllegalArgumentException("smallestSequence > largestSequence");
    }
    smallestKey = smallestKey.clone();
    largestKey = largestKey.clone();
  }

  @Override
  public byte[] smallestKey() {
    return smallestKey.clone();
  }

  @Override
  public byte[] largestKey() {
    return largestKey.clone();
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof FileMetadata that
        && level == that.level
        && number == that.number
        && sizeBytes == that.sizeBytes
        && smallestSequence == that.smallestSequence
        && largestSequence == that.largestSequence
        && Arrays.equals(smallestKey, that.smallestKey)
        && Arrays.equals(largestKey, that.largestKey);
  }

  @Override
  public int hashCode() {
    return Objects.hash(level, number, sizeBytes, smallestSequence, largestSequence)
        + 31 * Arrays.hashCode(smallestKey)
        + Arrays.hashCode(largestKey);
  }

  @Override
  public String toString() {
    return "FileMetadata[level=%d, number=%d, sizeBytes=%d, sequences=%d..%d, keys=%s..%s]"
        .formatted(
            level,
            number,
            sizeBytes,
            smallestSequence,
            largestSequence,
            HexFormat.of().formatHex(smallestKey),
            HexFormat.of().formatHex(largestKey));
  }
}
