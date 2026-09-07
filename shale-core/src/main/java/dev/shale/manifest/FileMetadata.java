package dev.shale.manifest;

import dev.shale.internal.annotations.Immutable;
import java.util.Arrays;

/**
 * A file added to the live set by a version edit (format.md §4, tag 6).
 *
 * <p>The key range is recorded even though M5 has no use for it: M6's compaction picks files by
 * overlapping range, and the writer already knows both keys when it finishes a table, so writing
 * them now costs one length-prefixed key each and saves a format version bump later (ADR-0012).
 * {@code level} is always {@code 0} until compaction creates deeper ones.
 *
 * @param level the level holding the file
 * @param fileNumber the file's number, from the engine's single file-number counter
 * @param sizeBytes the file's size on disk, used by M6's compaction scoring
 * @param smallest the smallest internal key in the file, encoded (ADR-0004)
 * @param largest the largest internal key in the file, encoded
 */
@Immutable
public record FileMetadata(
    int level, long fileNumber, long sizeBytes, byte[] smallest, byte[] largest) {

  /**
   * @throws IllegalArgumentException on a negative component or a null key (caller bug).
   */
  public FileMetadata {
    if (level < 0) {
      throw new IllegalArgumentException("level must not be negative: " + level);
    }
    if (fileNumber < 0) {
      throw new IllegalArgumentException("fileNumber must not be negative: " + fileNumber);
    }
    if (sizeBytes < 0) {
      throw new IllegalArgumentException("sizeBytes must not be negative: " + sizeBytes);
    }
    if (smallest == null || largest == null) {
      throw new IllegalArgumentException("smallest and largest must be non-null");
    }
    smallest = smallest.clone();
    largest = largest.clone();
  }

  /** The smallest internal key; a copy, so a caller cannot mutate this record's state. */
  @Override
  public byte[] smallest() {
    return smallest.clone();
  }

  /** The largest internal key; a copy. */
  @Override
  public byte[] largest() {
    return largest.clone();
  }

  /** Value equality over the key bytes — the record default would compare array identity. */
  @Override
  public boolean equals(Object other) {
    return other instanceof FileMetadata that
        && level == that.level
        && fileNumber == that.fileNumber
        && sizeBytes == that.sizeBytes
        && Arrays.equals(smallest, that.smallest)
        && Arrays.equals(largest, that.largest);
  }

  @Override
  public int hashCode() {
    int result = Integer.hashCode(level);
    result = 31 * result + Long.hashCode(fileNumber);
    result = 31 * result + Long.hashCode(sizeBytes);
    result = 31 * result + Arrays.hashCode(smallest);
    return 31 * result + Arrays.hashCode(largest);
  }

  @Override
  public String toString() {
    return "FileMetadata[level="
        + level
        + ", fileNumber="
        + fileNumber
        + ", sizeBytes="
        + sizeBytes
        + "]";
  }
}
