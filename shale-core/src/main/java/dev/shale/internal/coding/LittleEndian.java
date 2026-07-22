package dev.shale.internal.coding;

import dev.shale.internal.annotations.ThreadSafe;

/**
 * Little-endian fixed-width integer coding. Every fixed-width integer that will be written to disk
 * or the wire is little-endian, stated explicitly here rather than inherited from a platform
 * default (ADR-0005, on-disk-formats.md §2).
 *
 * <p><b>Threading:</b> stateless; all methods are pure functions over caller-owned arrays.
 */
@ThreadSafe
public final class LittleEndian {

  private LittleEndian() {}

  /** Writes {@code value} as 8 little-endian bytes at {@code dst[offset..offset+7]}. */
  public static void putFixed64(byte[] dst, int offset, long value) {
    dst[offset] = (byte) value;
    dst[offset + 1] = (byte) (value >>> 8);
    dst[offset + 2] = (byte) (value >>> 16);
    dst[offset + 3] = (byte) (value >>> 24);
    dst[offset + 4] = (byte) (value >>> 32);
    dst[offset + 5] = (byte) (value >>> 40);
    dst[offset + 6] = (byte) (value >>> 48);
    dst[offset + 7] = (byte) (value >>> 56);
  }

  /** Reads 8 little-endian bytes at {@code src[offset..offset+7]} as a long. */
  public static long getFixed64(byte[] src, int offset) {
    long value = 0;
    // Most-significant byte first, folding down to the least at src[offset].
    for (int i = 7; i >= 0; i--) {
      value = (value << 8) | (src[offset + i] & 0xFFL);
    }
    return value;
  }
}
