package dev.shale.manifest;

import dev.shale.internal.annotations.Immutable;
import dev.shale.internal.annotations.NotThreadSafe;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * The difference between one set of live files and the next: the record type of the manifest
 * ({@code format.md} §3). Every field is optional; an edit carries only what changed. The first
 * edit of each manifest carries the whole state.
 *
 * <p>Build one with {@link #builder()}.
 *
 * @param comparatorName the user-key comparator's name (tag 1)
 * @param logNumber the oldest WAL segment still needed (tag 2)
 * @param nextFileNumber the next file number to allocate (tag 3)
 * @param lastSequence the last sequence number used (tag 4)
 * @param compactionPointers where the next compaction of each level starts (tag 5; M6)
 * @param deletedFiles files that leave the live set (tag 6)
 * @param newFiles files that join the live set (tag 7)
 * @see <a href="https://github.com/google/leveldb/blob/main/db/version_edit.h">LevelDB
 *     version_edit.h</a>
 */
@Immutable
public record VersionEdit(
    Optional<String> comparatorName,
    OptionalLong logNumber,
    OptionalLong nextFileNumber,
    OptionalLong lastSequence,
    List<CompactionPointer> compactionPointers,
    List<DeletedFile> deletedFiles,
    List<FileMetadata> newFiles) {

  /** Copies the lists, so an edit cannot change after it is built. */
  public VersionEdit {
    compactionPointers = List.copyOf(compactionPointers);
    deletedFiles = List.copyOf(deletedFiles);
    newFiles = List.copyOf(newFiles);
  }

  /** An empty builder. */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Where the next compaction of {@code level} starts: after {@code internalKey}.
   *
   * @param level the level, 0 to 6
   * @param internalKey an encoded internal key
   */
  @Immutable
  public record CompactionPointer(int level, byte[] internalKey) {

    /** Rejects a level outside 0–6. */
    public CompactionPointer {
      if (level < 0 || level >= ManifestFormat.NUM_LEVELS) {
        throw new IllegalArgumentException("level out of range: " + level);
      }
      internalKey = internalKey.clone();
    }

    @Override
    public byte[] internalKey() {
      return internalKey.clone();
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof CompactionPointer that
          && level == that.level
          && Arrays.equals(internalKey, that.internalKey);
    }

    @Override
    public int hashCode() {
      return 31 * level + Arrays.hashCode(internalKey);
    }

    @Override
    public String toString() {
      return "CompactionPointer[level="
          + level
          + ", "
          + HexFormat.of().formatHex(internalKey)
          + "]";
    }
  }

  /**
   * A file that leaves the live set.
   *
   * @param level the level it was at, 0 to 6
   * @param number its file number
   */
  @Immutable
  public record DeletedFile(int level, long number) {

    /** Rejects a level outside 0–6 and a negative number. */
    public DeletedFile {
      if (level < 0 || level >= ManifestFormat.NUM_LEVELS) {
        throw new IllegalArgumentException("level out of range: " + level);
      }
      if (number < 0) {
        throw new IllegalArgumentException("negative file number");
      }
    }
  }

  /**
   * Builds a {@link VersionEdit}.
   *
   * <p><b>Threading:</b> not thread-safe; owned by the thread building the edit.
   */
  @NotThreadSafe
  public static final class Builder {

    private String comparatorName;
    private Long logNumber;
    private Long nextFileNumber;
    private Long lastSequence;
    private final List<CompactionPointer> compactionPointers = new ArrayList<>();
    private final List<DeletedFile> deletedFiles = new ArrayList<>();
    private final List<FileMetadata> newFiles = new ArrayList<>();

    private Builder() {}

    /** Sets tag 1. */
    public Builder comparatorName(String name) {
      this.comparatorName = name;
      return this;
    }

    /** Sets tag 2. */
    public Builder logNumber(long number) {
      this.logNumber = number;
      return this;
    }

    /** Sets tag 3. */
    public Builder nextFileNumber(long number) {
      this.nextFileNumber = number;
      return this;
    }

    /** Sets tag 4. */
    public Builder lastSequence(long sequence) {
      this.lastSequence = sequence;
      return this;
    }

    /** Adds a tag 5. */
    public Builder addCompactionPointer(int level, byte[] internalKey) {
      compactionPointers.add(new CompactionPointer(level, internalKey));
      return this;
    }

    /** Adds a tag 6. */
    public Builder deleteFile(int level, long number) {
      deletedFiles.add(new DeletedFile(level, number));
      return this;
    }

    /** Adds a tag 7. */
    public Builder addFile(FileMetadata file) {
      newFiles.add(file);
      return this;
    }

    /** The edit built so far. */
    public VersionEdit build() {
      return new VersionEdit(
          Optional.ofNullable(comparatorName),
          logNumber == null ? OptionalLong.empty() : OptionalLong.of(logNumber),
          nextFileNumber == null ? OptionalLong.empty() : OptionalLong.of(nextFileNumber),
          lastSequence == null ? OptionalLong.empty() : OptionalLong.of(lastSequence),
          compactionPointers,
          deletedFiles,
          newFiles);
    }
  }
}
