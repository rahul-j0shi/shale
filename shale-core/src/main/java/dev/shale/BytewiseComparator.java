package dev.shale;

import dev.shale.internal.annotations.ThreadSafe;

/**
 * Unsigned lexicographic ordering — LevelDB's default. Byte {@code 0xFF} sorts after {@code 0x01};
 * a shorter key that is a prefix of a longer one sorts first.
 *
 * <p><b>Threading:</b> stateless singleton.
 *
 * @see <a href="https://github.com/google/leveldb/blob/main/util/comparator.cc">LevelDB
 *     comparator.cc</a>
 */
@ThreadSafe
public final class BytewiseComparator implements KeyComparator {

  public static final BytewiseComparator INSTANCE = new BytewiseComparator();

  private BytewiseComparator() {}

  @Override
  public int compare(ByteRange a, ByteRange b) {
    int min = Math.min(a.length(), b.length());
    byte[] aa = a.array();
    byte[] bb = b.array();
    for (int i = 0; i < min; i++) {
      int x = aa[a.offset() + i] & 0xFF;
      int y = bb[b.offset() + i] & 0xFF;
      if (x != y) {
        return x - y;
      }
    }
    return a.length() - b.length();
  }

  @Override
  public String name() {
    return "shale.BytewiseComparator";
  }
}
