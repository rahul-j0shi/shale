package dev.shale;

import dev.shale.internal.annotations.Immutable;

/**
 * A view over {@code array[offset, offset+length)} — the unit a {@link KeyComparator} orders. Lets
 * comparators inspect a sub-range (a user-key prefix of an encoded internal key) without copying,
 * while keeping method arity low.
 *
 * <p><b>Threading:</b> immutable value; the referenced array is caller-owned and must not be
 * mutated while the range is in use.
 *
 * @param array the backing array (caller-owned; not copied)
 * @param offset start index of the range, inclusive
 * @param length number of bytes in the range
 */
@Immutable
public record ByteRange(byte[] array, int offset, int length) {

  public ByteRange {
    if (array == null) {
      throw new IllegalArgumentException("array is null");
    }
    if (offset < 0 || length < 0 || offset + length > array.length) {
      throw new IllegalArgumentException(
          "range [" + offset + "," + (offset + length) + ") outside array of " + array.length);
    }
  }

  /** A range spanning the whole array. */
  public static ByteRange of(byte[] array) {
    return new ByteRange(array, 0, array.length);
  }
}
