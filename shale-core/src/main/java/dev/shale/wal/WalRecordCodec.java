package dev.shale.wal;

import dev.shale.CorruptionException;
import dev.shale.internal.annotations.ThreadSafe;
import dev.shale.internal.coding.Varints;
import dev.shale.internal.key.InternalKey;
import java.util.Arrays;

/**
 * Encodes and decodes a WAL logical-record payload: one mutation as {@code internalKeyLen ‖
 * internalKey ‖ valueLen ‖ value} (format.md §4). The internal key carries the user key, sequence
 * number, and value type; {@code value} is empty for a delete.
 *
 * <p><b>Threading:</b> stateless; pure functions over caller-owned arrays.
 */
@ThreadSafe
public final class WalRecordCodec {

  private WalRecordCodec() {}

  /** Encodes {@code (key, value)} into a WAL record payload. */
  public static byte[] encode(InternalKey key, byte[] value) {
    byte[] internalKey = key.encode();
    int size =
        Varints.size(internalKey.length)
            + internalKey.length
            + Varints.size(value.length)
            + value.length;
    byte[] out = new byte[size];
    int pos = Varints.put(out, 0, internalKey.length);
    System.arraycopy(internalKey, 0, out, pos, internalKey.length);
    pos += internalKey.length;
    pos = Varints.put(out, pos, value.length);
    System.arraycopy(value, 0, out, pos, value.length);
    return out;
  }

  /**
   * Decodes a WAL record payload, under ADR-0024 §6's decoder contract (N4):
   *
   * <ul>
   *   <li>each length is a {@code varint32}, decoded wide and rejected at 2^32 or more;
   *   <li>it is checked against the bytes remaining <em>by subtraction, as a {@code long}</em>,
   *       before it is narrowed or anything is allocated. A length that once narrowed to a
   *       plausible small number (2^32 + 9 → 9) cannot (SH-13);
   *   <li>the payload is consumed exactly: bytes after the value are corruption, since format v1
   *       defines none.
   * </ul>
   */
  public static Decoded decode(byte[] payload) {
    Varints.Decoded keyLength = Varints.getUnsigned32(payload, 0);
    int keyStart = keyLength.nextOffset();
    byte[] internalKey = slice(payload, keyStart, keyLength.value(), "internal key");

    Varints.Decoded valueLength = Varints.getUnsigned32(payload, keyStart + internalKey.length);
    int valueStart = valueLength.nextOffset();
    byte[] value = slice(payload, valueStart, valueLength.value(), "value");

    int end = valueStart + value.length;
    if (end != payload.length) {
      throw new CorruptionException(
          "trailing bytes after the WAL record value", end, 0, payload.length - end);
    }
    return new Decoded(InternalKey.decode(internalKey), value);
  }

  private static byte[] slice(byte[] payload, int start, long length, String field) {
    long remaining = (long) payload.length - start;
    if (length > remaining) {
      throw new CorruptionException(
          "WAL record " + field + " length overruns the payload", start, remaining, length);
    }
    return Arrays.copyOfRange(payload, start, start + (int) length);
  }

  /**
   * A decoded WAL mutation.
   *
   * @param internalKey the mutation's internal key (user key, sequence, value type)
   * @param value the value bytes; empty for a delete
   */
  public record Decoded(InternalKey internalKey, byte[] value) {}
}
