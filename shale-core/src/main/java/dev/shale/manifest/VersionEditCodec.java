package dev.shale.manifest;

import dev.shale.CorruptionException;
import dev.shale.internal.annotations.NotThreadSafe;
import dev.shale.internal.annotations.ThreadSafe;
import dev.shale.internal.coding.Varints;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.BitSet;

/**
 * Encodes and decodes one {@link VersionEdit} as a manifest record payload ({@code format.md} §3):
 * {@code (tag, field)} pairs, with LevelDB's tag numbers.
 *
 * <p><b>Decoding follows ADR-0024 §6.</b> Every varint32 field is decoded wide and rejected at
 * 2^32; every length is checked against the bytes remaining by subtraction, as a {@code long},
 * before it is narrowed or anything is allocated; the payload is consumed exactly. An unknown tag,
 * a level outside 0–6, a number of 2^63 or more, a scalar tag given twice, an internal key shorter
 * than its trailer, and an inverted sequence range are corruption (N4). The offsets in a {@link
 * CorruptionException} are relative to the payload; the reader that knows the file adds it.
 *
 * <p><b>Threading:</b> stateless.
 *
 * @see <a href="https://github.com/google/leveldb/blob/main/db/version_edit.cc">LevelDB
 *     version_edit.cc</a>
 */
@ThreadSafe
public final class VersionEditCodec {

  /** An internal key ends in an 8-byte trailer: {@code sequence << 8 | type} (ADR-0004). */
  private static final int INTERNAL_KEY_TRAILER_BYTES = 8;

  private VersionEditCodec() {}

  /** The payload for {@code edit}: its tags in ascending order. */
  public static byte[] encode(VersionEdit edit) {
    Encoder out = new Encoder();
    edit.comparatorName()
        .ifPresent(
            name ->
                out.varint(ManifestFormat.TAG_COMPARATOR)
                    .lengthPrefixed(name.getBytes(StandardCharsets.UTF_8)));
    edit.logNumber().ifPresent(n -> out.varint(ManifestFormat.TAG_LOG_NUMBER).varint(n));
    edit.nextFileNumber().ifPresent(n -> out.varint(ManifestFormat.TAG_NEXT_FILE_NUMBER).varint(n));
    edit.lastSequence().ifPresent(n -> out.varint(ManifestFormat.TAG_LAST_SEQUENCE).varint(n));
    for (VersionEdit.CompactionPointer pointer : edit.compactionPointers()) {
      out.varint(ManifestFormat.TAG_COMPACTION_POINTER)
          .varint(pointer.level())
          .lengthPrefixed(pointer.internalKey());
    }
    for (VersionEdit.DeletedFile file : edit.deletedFiles()) {
      out.varint(ManifestFormat.TAG_DELETED_FILE).varint(file.level()).varint(file.number());
    }
    for (FileMetadata file : edit.newFiles()) {
      out.varint(ManifestFormat.TAG_NEW_FILE)
          .varint(file.level())
          .varint(file.number())
          .varint(file.sizeBytes())
          .varint(file.smallestSequence())
          .varint(file.largestSequence())
          .lengthPrefixed(file.smallestKey())
          .lengthPrefixed(file.largestKey());
    }
    return out.toByteArray();
  }

  /** Decodes one payload; any deviation from {@code format.md} §3 is corruption. */
  public static VersionEdit decode(byte[] payload) {
    Decoder in = new Decoder(payload);
    VersionEdit.Builder edit = VersionEdit.builder();
    BitSet seen = new BitSet();
    while (in.hasMore()) {
      int tagOffset = in.position();
      int tag = (int) in.varint32("tag");
      if (tag <= ManifestFormat.TAG_LAST_SEQUENCE && tag >= ManifestFormat.TAG_COMPARATOR) {
        if (seen.get(tag)) {
          throw new CorruptionException("version edit repeats tag " + tag, tagOffset, 1, 2);
        }
        seen.set(tag);
      }
      switch (tag) {
        case ManifestFormat.TAG_COMPARATOR -> edit.comparatorName(in.utf8("comparator name"));
        case ManifestFormat.TAG_LOG_NUMBER -> edit.logNumber(in.varint64());
        case ManifestFormat.TAG_NEXT_FILE_NUMBER -> edit.nextFileNumber(in.varint64());
        case ManifestFormat.TAG_LAST_SEQUENCE -> edit.lastSequence(in.varint64());
        case ManifestFormat.TAG_COMPACTION_POINTER ->
            edit.addCompactionPointer(in.level(), in.internalKey("compaction pointer"));
        case ManifestFormat.TAG_DELETED_FILE -> edit.deleteFile(in.level(), in.varint64());
        case ManifestFormat.TAG_NEW_FILE -> edit.addFile(newFile(in));
        default ->
            throw new CorruptionException(
                "unknown version edit tag", tagOffset, ManifestFormat.TAG_NEW_FILE, tag);
      }
    }
    return edit.build();
  }

  private static FileMetadata newFile(Decoder in) {
    int offset = in.position();
    int level = in.level();
    long number = in.varint64();
    long size = in.varint64();
    long smallestSequence = in.varint64();
    long largestSequence = in.varint64();
    byte[] smallestKey = in.internalKey("smallest key");
    byte[] largestKey = in.internalKey("largest key");
    if (smallestSequence > largestSequence) {
      throw new CorruptionException(
          "new file's smallest sequence exceeds its largest",
          offset,
          largestSequence,
          smallestSequence);
    }
    return new FileMetadata(
        level, number, size, smallestSequence, largestSequence, smallestKey, largestKey);
  }

  /** Appends varints and length-prefixed bytes to a growing buffer. */
  @NotThreadSafe
  private static final class Encoder {

    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private final byte[] scratch = new byte[10];

    Encoder varint(long value) {
      int end = Varints.put(scratch, 0, value);
      bytes.write(scratch, 0, end);
      return this;
    }

    Encoder lengthPrefixed(byte[] data) {
      varint(data.length);
      bytes.write(data, 0, data.length);
      return this;
    }

    byte[] toByteArray() {
      return bytes.toByteArray();
    }
  }

  /** Reads fields from a payload, checking each against what remains. */
  @NotThreadSafe
  private static final class Decoder {

    private final byte[] payload;
    private int position;

    Decoder(byte[] payload) {
      this.payload = payload;
    }

    boolean hasMore() {
      return position < payload.length;
    }

    int position() {
      return position;
    }

    /** A varint64 field; every one in this format is a count or a number below 2^63. */
    long varint64() {
      int offset = position;
      Varints.Decoded decoded = Varints.get(payload, position);
      if (decoded.value() < 0) {
        throw new CorruptionException(
            "version edit field exceeds 63 bits", offset, Long.MAX_VALUE, decoded.value());
      }
      position = decoded.nextOffset();
      return decoded.value();
    }

    long varint32(String field) {
      Varints.Decoded decoded = Varints.getUnsigned32(payload, position);
      position = decoded.nextOffset();
      return decoded.value();
    }

    int level() {
      int offset = position;
      long level = varint32("level");
      if (level >= ManifestFormat.NUM_LEVELS) {
        throw new CorruptionException(
            "version edit level out of range", offset, ManifestFormat.NUM_LEVELS - 1, level);
      }
      return (int) level;
    }

    byte[] bytes(String field) {
      int offset = position;
      long length = varint32(field);
      long remaining = (long) payload.length - position;
      if (length > remaining) {
        throw new CorruptionException(
            "version edit " + field + " overruns the record", offset, remaining, length);
      }
      byte[] out = Arrays.copyOfRange(payload, position, position + (int) length);
      position += (int) length;
      return out;
    }

    byte[] internalKey(String field) {
      int offset = position;
      byte[] key = bytes(field);
      if (key.length < INTERNAL_KEY_TRAILER_BYTES) {
        throw new CorruptionException(
            "version edit " + field + " shorter than an internal key's trailer",
            offset,
            INTERNAL_KEY_TRAILER_BYTES,
            key.length);
      }
      return key;
    }

    String utf8(String field) {
      int offset = position;
      byte[] encoded = bytes(field);
      try {
        return StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(encoded))
            .toString();
      } catch (CharacterCodingException e) {
        throw new CorruptionException(
            "version edit " + field + " is not valid UTF-8", offset, -1, -1);
      }
    }
  }
}
