package dev.shale.manifest;

import dev.shale.CorruptionException;
import dev.shale.internal.annotations.Immutable;
import dev.shale.internal.annotations.NotThreadSafe;
import dev.shale.internal.coding.Varints;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * One entry in the manifest: the difference between one version of the live file set and the next
 * (ADR-0012). A version is the fold of every edit in the manifest, applied in order, so an edit
 * carries only what changed — an absent field means "unchanged".
 *
 * <p>The payload is a sequence of tagged fields, LevelDB's {@code VersionEdit} encoding (format.md
 * §4): a varint tag, then that tag's value, repeated until the payload is exhausted. Tags 5 and 6
 * may repeat; the rest appear at most once.
 *
 * <p><b>An unknown tag is {@link CorruptionException}, never a skipped field.</b> A manifest is a
 * statement about which files exist; stepping over a field we cannot interpret risks silently
 * dropping a file, which is exactly what N4 forbids. The format is therefore deliberately not
 * forward compatible: an older reader must refuse a newer manifest rather than guess at it.
 *
 * <p><b>Threading:</b> immutable and safe to share; build one with {@link Builder}, which is not.
 *
 * @see <a href="https://github.com/google/leveldb/blob/main/db/version_edit.cc">LevelDB
 *     version_edit.cc</a>
 */
@Immutable
public final class VersionEdit {

  private static final int TAG_COMPARATOR_NAME = 1;
  private static final int TAG_LOG_NUMBER = 2;
  private static final int TAG_NEXT_FILE_NUMBER = 3;
  private static final int TAG_LAST_SEQUENCE = 4;
  private static final int TAG_DELETED_FILE = 5;
  private static final int TAG_ADDED_FILE = 6;

  private final String comparatorName;
  private final Long logNumber;
  private final Long nextFileNumber;
  private final Long lastSequence;
  private final List<FileMetadata> addedFiles;
  private final List<DeletedFile> deletedFiles;

  private VersionEdit(Builder builder) {
    this.comparatorName = builder.comparatorName;
    this.logNumber = builder.logNumber;
    this.nextFileNumber = builder.nextFileNumber;
    this.lastSequence = builder.lastSequence;
    this.addedFiles = List.copyOf(builder.addedFiles);
    this.deletedFiles = List.copyOf(builder.deletedFiles);
  }

  /** The comparator this database is ordered by; present in a manifest's first edit. */
  public Optional<String> comparatorName() {
    return Optional.ofNullable(comparatorName);
  }

  /** WAL segments numbered below this are covered by a flush and need no replay. */
  public Optional<Long> logNumber() {
    return Optional.ofNullable(logNumber);
  }

  /** The next unused file number, shared by {@code .wal}, {@code .sst} and {@code .manifest}. */
  public Optional<Long> nextFileNumber() {
    return Optional.ofNullable(nextFileNumber);
  }

  /** The highest sequence number assigned when this edit was written. */
  public Optional<Long> lastSequence() {
    return Optional.ofNullable(lastSequence);
  }

  /** Files this edit adds to the live set, in the order they were added. */
  public List<FileMetadata> addedFiles() {
    return addedFiles;
  }

  /** Files this edit removes from the live set, in the order they were removed. */
  public List<DeletedFile> deletedFiles() {
    return deletedFiles;
  }

  /** Encodes this edit as a manifest record payload (format.md §4). */
  public byte[] encode() {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    if (comparatorName != null) {
      putTag(out, TAG_COMPARATOR_NAME);
      putBytes(out, comparatorName.getBytes(StandardCharsets.UTF_8));
    }
    putScalar(out, TAG_LOG_NUMBER, logNumber);
    putScalar(out, TAG_NEXT_FILE_NUMBER, nextFileNumber);
    putScalar(out, TAG_LAST_SEQUENCE, lastSequence);
    for (DeletedFile deleted : deletedFiles) {
      putTag(out, TAG_DELETED_FILE);
      putVarint(out, deleted.level());
      putVarint(out, deleted.fileNumber());
    }
    for (FileMetadata added : addedFiles) {
      putTag(out, TAG_ADDED_FILE);
      putVarint(out, added.level());
      putVarint(out, added.fileNumber());
      putVarint(out, added.sizeBytes());
      putBytes(out, added.smallest());
      putBytes(out, added.largest());
    }
    return out.toByteArray();
  }

  /**
   * Decodes a manifest record payload.
   *
   * @throws CorruptionException on an unknown tag, a truncated field, or a length that overruns the
   *     payload — each carrying the offset it was found at (N4)
   */
  public static VersionEdit decode(byte[] payload) {
    Builder builder = new Builder();
    Cursor cursor = new Cursor(payload);
    while (cursor.hasRemaining()) {
      int tagOffset = cursor.offset();
      long tag = cursor.varint("tag");
      switch ((int) tag) {
        case TAG_COMPARATOR_NAME ->
            builder.comparatorName(
                new String(cursor.bytes("comparator name"), StandardCharsets.UTF_8));
        case TAG_LOG_NUMBER -> builder.logNumber(cursor.varint("log number"));
        case TAG_NEXT_FILE_NUMBER -> builder.nextFileNumber(cursor.varint("next file number"));
        case TAG_LAST_SEQUENCE -> builder.lastSequence(cursor.varint("last sequence"));
        case TAG_DELETED_FILE ->
            builder.deleteFile(
                (int) cursor.varint("deleted file level"), cursor.varint("deleted file number"));
        case TAG_ADDED_FILE ->
            builder.addFile(
                new FileMetadata(
                    (int) cursor.varint("added file level"),
                    cursor.varint("added file number"),
                    cursor.varint("added file size"),
                    cursor.bytes("added file smallest key"),
                    cursor.bytes("added file largest key")));
        default ->
            throw new CorruptionException(
                "unknown version edit tag in manifest record", tagOffset, -1, tag);
      }
    }
    return builder.build();
  }

  private static void putScalar(ByteArrayOutputStream out, int tag, Long value) {
    if (value != null) {
      putTag(out, tag);
      putVarint(out, value);
    }
  }

  private static void putTag(ByteArrayOutputStream out, int tag) {
    putVarint(out, tag);
  }

  private static void putBytes(ByteArrayOutputStream out, byte[] bytes) {
    putVarint(out, bytes.length);
    out.write(bytes, 0, bytes.length);
  }

  private static void putVarint(ByteArrayOutputStream out, long value) {
    byte[] buffer = new byte[Varints.size(value)];
    Varints.put(buffer, 0, value);
    out.write(buffer, 0, buffer.length);
  }

  /** Reads tagged fields left to right, turning every short read into corruption with an offset. */
  @NotThreadSafe
  private static final class Cursor {

    private final byte[] payload;
    private int offset;

    Cursor(byte[] payload) {
      this.payload = payload;
    }

    boolean hasRemaining() {
      return offset < payload.length;
    }

    int offset() {
      return offset;
    }

    long varint(String field) {
      if (offset >= payload.length) {
        throw new CorruptionException(
            "manifest record ended before " + field, offset, -1, payload.length);
      }
      Varints.Decoded decoded;
      try {
        decoded = Varints.get(payload, offset);
      } catch (CorruptionException e) {
        throw new CorruptionException(
            "malformed varint for " + field + " in manifest record", offset, -1, -1);
      }
      offset = decoded.nextOffset();
      return decoded.value();
    }

    byte[] bytes(String field) {
      long length = varint(field + " length");
      if (length < 0 || offset + length > payload.length) {
        throw new CorruptionException(
            field + " overruns the manifest record", offset, payload.length - offset, length);
      }
      byte[] slice = Arrays.copyOfRange(payload, offset, offset + (int) length);
      offset += (int) length;
      return slice;
    }
  }

  /** Accumulates the fields of one edit. Not thread-safe; build it on one thread and share it. */
  @NotThreadSafe
  public static final class Builder {

    private String comparatorName;
    private Long logNumber;
    private Long nextFileNumber;
    private Long lastSequence;
    private final List<FileMetadata> addedFiles = new ArrayList<>();
    private final List<DeletedFile> deletedFiles = new ArrayList<>();

    /** Records the comparator this database is ordered by. */
    public Builder comparatorName(String name) {
      this.comparatorName = name;
      return this;
    }

    /** Records that WAL segments below {@code number} are covered by a flush. */
    public Builder logNumber(long number) {
      this.logNumber = number;
      return this;
    }

    /** Records the next unused file number. */
    public Builder nextFileNumber(long number) {
      this.nextFileNumber = number;
      return this;
    }

    /** Records the highest sequence number assigned so far. */
    public Builder lastSequence(long sequence) {
      this.lastSequence = sequence;
      return this;
    }

    /** Adds a file to the live set. */
    public Builder addFile(FileMetadata file) {
      addedFiles.add(file);
      return this;
    }

    /** Removes a file from the live set. */
    public Builder deleteFile(int level, long fileNumber) {
      deletedFiles.add(new DeletedFile(level, fileNumber));
      return this;
    }

    /** The immutable edit. */
    public VersionEdit build() {
      return new VersionEdit(this);
    }
  }
}
