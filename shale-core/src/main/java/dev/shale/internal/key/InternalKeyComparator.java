package dev.shale.internal.key;

import dev.shale.ByteRange;
import dev.shale.KeyComparator;
import dev.shale.internal.annotations.ThreadSafe;
import dev.shale.internal.coding.LittleEndian;

/**
 * Orders encoded internal keys: user key ascending by the wrapped {@link KeyComparator}, then the
 * 8-byte trailer descending so the newest sequence (largest trailer) sorts first and a seek lands
 * directly on it (ADR-0004).
 *
 * <p><b>Threading:</b> stateless; safe for concurrent use if the wrapped comparator is.
 */
@ThreadSafe
public final class InternalKeyComparator implements KeyComparator {

  private static final int TRAILER_BYTES = 8;

  private final KeyComparator userComparator;

  public InternalKeyComparator(KeyComparator userComparator) {
    this.userComparator = userComparator;
  }

  @Override
  public int compare(ByteRange a, ByteRange b) {
    if (a.length() < TRAILER_BYTES || b.length() < TRAILER_BYTES) {
      throw new IllegalArgumentException("encoded internal key shorter than trailer");
    }
    int aUserLen = a.length() - TRAILER_BYTES;
    int bUserLen = b.length() - TRAILER_BYTES;
    ByteRange aUser = new ByteRange(a.array(), a.offset(), aUserLen);
    ByteRange bUser = new ByteRange(b.array(), b.offset(), bUserLen);
    int c = userComparator.compare(aUser, bUser);
    if (c != 0) {
      return c;
    }
    long aTrailer = LittleEndian.getFixed64(a.array(), a.offset() + aUserLen);
    long bTrailer = LittleEndian.getFixed64(b.array(), b.offset() + bUserLen);
    // Descending: larger trailer (newer) is "less" so it sorts first.
    return Long.compareUnsigned(bTrailer, aTrailer);
  }

  @Override
  public String name() {
    return "shale.InternalKeyComparator(" + userComparator.name() + ")";
  }
}
