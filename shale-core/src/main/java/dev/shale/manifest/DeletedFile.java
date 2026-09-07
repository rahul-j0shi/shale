package dev.shale.manifest;

import dev.shale.internal.annotations.Immutable;

/**
 * A file removed from the live set by a version edit, identified by the pair that names it
 * uniquely: its level and its file number (format.md §4, tag 5).
 *
 * <p>The file itself is not deleted when this record is written — only when the last {@code
 * Version} referencing it is dropped and no cursor still pins it (ADR-0012, delete-on-zero).
 *
 * @param level the level the file was in; always {@code 0} until compaction (M6)
 * @param fileNumber the file's number, from the engine's single file-number counter
 */
@Immutable
public record DeletedFile(int level, long fileNumber) {

  /**
   * @throws IllegalArgumentException if either component is negative (caller bug, not data).
   */
  public DeletedFile {
    if (level < 0) {
      throw new IllegalArgumentException("level must not be negative: " + level);
    }
    if (fileNumber < 0) {
      throw new IllegalArgumentException("fileNumber must not be negative: " + fileNumber);
    }
  }
}
