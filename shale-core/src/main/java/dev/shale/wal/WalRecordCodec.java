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

  /** Decodes a WAL record payload; a length that overruns the payload is corruption (N4). */
  public static Decoded decode(byte[] payload) {
    Varints.Decoded keyLen = Varints.get(payload, 0);
    int keyStart = keyLen.nextOffset();
    int keyBytes = (int) keyLen.value();
    byte[] internalKey = slice(payload, keyStart, keyBytes);

    Varints.Decoded valueLen = Varints.get(payload, keyStart + keyBytes);
    int valueStart = valueLen.nextOffset();
    int valueBytes = (int) valueLen.value();
    byte[] value = slice(payload, valueStart, valueBytes);

    return new Decoded(InternalKey.decode(internalKey), value);
  }

  private static byte[] slice(byte[] payload, int start, int length) {
    if (length < 0 || start + length > payload.length) {
      throw new CorruptionException(
          "WAL record field overruns payload", start, length, payload.length - start);
    }
    return Arrays.copyOfRange(payload, start, start + length);
  }

  /**
   * A decoded WAL mutation.
   *
   * @param internalKey the mutation's internal key (user key, sequence, value type)
   * @param value the value bytes; empty for a delete
   */
  public record Decoded(InternalKey internalKey, byte[] value) {}
}
