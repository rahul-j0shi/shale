package dev.shale.internal.coding;

import dev.shale.internal.annotations.ThreadSafe;
import java.util.zip.CRC32C;

/**
 * CRC32C (Castagnoli) over byte ranges, computed with {@link java.util.zip.CRC32C}. A checksum is
 * general plumbing, not a project subject, so the JDK implementation is used directly
 * (java-style.md §1). Returned as an {@code int} to be stored as {@code fixed32LE}.
 *
 * <p><b>Threading:</b> stateless; each call uses its own {@link CRC32C} accumulator.
 */
@ThreadSafe
public final class Crc32c {

  private Crc32c() {}

  /** CRC32C over {@code data[offset, offset+length)}. */
  public static int of(byte[] data, int offset, int length) {
    CRC32C crc = new CRC32C();
    crc.update(data, offset, length);
    return (int) crc.getValue();
  }

  /** CRC32C over the whole array. */
  public static int of(byte[] data) {
    return of(data, 0, data.length);
  }
}
