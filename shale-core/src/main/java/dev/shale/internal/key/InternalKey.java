package dev.shale.internal.key;

import dev.shale.CorruptionException;
import dev.shale.internal.annotations.Immutable;
import dev.shale.internal.coding.LittleEndian;
import java.util.Arrays;

/**
 * user key + (56-bit sequence number, 8-bit value type), the engine's load-bearing key encoding
 * (ADR-0004, on-disk-formats.md §5). Encoded form is {@code userKey || fixed64LE((sequence << 8) |
 * typeCode)}.
 *
 * <p>Sort order (see {@link InternalKeyComparator}): user key ascending, then trailer descending,
 * so the newest version of a key sorts first.
 *
 * <p><b>Threading:</b> value type; {@code equals}/{@code hashCode} are by value (the {@code byte[]}
 * component forces an explicit override). The referenced {@code userKey} array is caller-owned;
 * callers must not mutate it while the key is in use.
 *
 * @param userKey the caller's key bytes (caller-owned; not copied)
 * @param sequenceNumber the mutation's sequence number, in {@code [0, MAX_SEQUENCE]}
 * @param valueType whether this record is a value or a tombstone
 * @see <a href="https://github.com/google/leveldb/blob/main/db/dbformat.h">LevelDB dbformat.h</a>
 */
@Immutable
public record InternalKey(byte[] userKey, long sequenceNumber, ValueType valueType) {

  /** 56 bits of sequence space; a larger value is a caller bug. */
  public static final long MAX_SEQUENCE = (1L << 56) - 1;

  private static final int TRAILER_BYTES = 8;

  public InternalKey {
    if (userKey == null) {
      throw new IllegalArgumentException("userKey is null");
    }
    if (valueType == null) {
      throw new IllegalArgumentException("valueType is null");
    }
    if (sequenceNumber < 0 || sequenceNumber > MAX_SEQUENCE) {
      throw new IllegalArgumentException("sequenceNumber out of 56-bit range: " + sequenceNumber);
    }
  }

  /** Packs {@code (sequence << 8) | typeCode} into the fixed64 trailer value. */
  public static long packTrailer(long sequenceNumber, ValueType valueType) {
    return (sequenceNumber << 8) | (valueType.code() & 0xFF);
  }

  public int encodedLength() {
    return userKey.length + TRAILER_BYTES;
  }

  public byte[] encode() {
    byte[] out = new byte[encodedLength()];
    System.arraycopy(userKey, 0, out, 0, userKey.length);
    LittleEndian.putFixed64(out, userKey.length, packTrailer(sequenceNumber, valueType));
    return out;
  }

  /** Decodes an encoded internal key; a length below the 8-byte trailer is corruption. */
  public static InternalKey decode(byte[] encoded) {
    if (encoded.length < TRAILER_BYTES) {
      throw new CorruptionException(
          "internal key shorter than trailer", 0, TRAILER_BYTES, encoded.length);
    }
    int userLen = encoded.length - TRAILER_BYTES;
    long trailer = LittleEndian.getFixed64(encoded, userLen);
    ValueType type = ValueType.fromCode((byte) (trailer & 0xFF));
    return new InternalKey(Arrays.copyOf(encoded, userLen), trailer >>> 8, type);
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof InternalKey k
        && sequenceNumber == k.sequenceNumber
        && valueType == k.valueType
        && Arrays.equals(userKey, k.userKey);
  }

  @Override
  public int hashCode() {
    return (Arrays.hashCode(userKey) * 31 + Long.hashCode(sequenceNumber)) * 31
        + valueType.hashCode();
  }

  @Override
  public String toString() {
    return "InternalKey[len="
        + userKey.length
        + ", seq="
        + sequenceNumber
        + ", type="
        + valueType
        + "]";
  }
}
