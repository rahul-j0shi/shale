package dev.shale.internal.coding;

import dev.shale.CorruptionException;
import dev.shale.internal.annotations.ThreadSafe;

/**
 * LevelDB-style variable-length integers. Each byte carries 7 value bits, low group first, with the
 * high bit set on every byte except the last. A 64-bit value takes at most 10 bytes; a run longer
 * than that, or one that ends before its terminator, is corruption (N4).
 *
 * <p><b>The tenth byte carries one bit.</b> Nine bytes hold 63 value bits, so a tenth byte may only
 * be {@code 0x00} or {@code 0x01}. Anything larger describes a number beyond 64 bits, which Java's
 * shift would silently wrap (to 0, for {@code 0x02}); it is corruption instead (SH-11, ADR-0024
 * §6). A width narrower than 64 bits is the consuming format's to enforce: fields the formats call
 * {@code varint32} decode through {@link #getUnsigned32}.
 *
 * <p><b>Threading:</b> stateless; pure functions over caller-owned arrays.
 *
 * @see <a href="https://github.com/google/leveldb/blob/main/util/coding.cc">LevelDB coding.cc</a>
 */
@ThreadSafe
public final class Varints {

  private static final int MAX_BYTES = 10;

  /** The shift of the tenth byte, which has room for only one value bit (bit 63). */
  private static final int LAST_BYTE_SHIFT = 63;

  private Varints() {}

  /** Number of bytes {@code value} encodes to (treated as unsigned 64-bit). */
  public static int size(long value) {
    int bytes = 1;
    long remaining = value;
    while ((remaining & ~0x7FL) != 0) {
      remaining >>>= 7;
      bytes++;
    }
    return bytes;
  }

  /** Writes {@code value} at {@code offset}; returns the index just past the last byte. */
  public static int put(byte[] dst, int offset, long value) {
    long remaining = value;
    int index = offset;
    while ((remaining & ~0x7FL) != 0) {
      dst[index++] = (byte) ((remaining & 0x7F) | 0x80);
      remaining >>>= 7;
    }
    dst[index++] = (byte) (remaining & 0x7F);
    return index;
  }

  /** Decodes the varint starting at {@code offset}. */
  public static Decoded get(byte[] src, int offset) {
    long result = 0;
    int shift = 0;
    int index = offset;
    while (shift < MAX_BYTES * 7) {
      if (index >= src.length) {
        throw new CorruptionException("truncated varint", offset, -1, -1);
      }
      int current = src[index++] & 0xFF;
      if (shift == LAST_BYTE_SHIFT && current > 1) {
        throw new CorruptionException("varint overflows 64 bits", index - 1, 1, current);
      }
      result |= (long) (current & 0x7F) << shift;
      if ((current & 0x80) == 0) {
        return new Decoded(result, index);
      }
      shift += 7;
    }
    throw new CorruptionException("overlong varint", offset, MAX_BYTES, index - offset);
  }

  /**
   * A decoded varint and the offset just past it.
   *
   * @param value the decoded value
   * @param nextOffset index of the first byte after the varint
   */
  public record Decoded(long value, int nextOffset) {}
}
