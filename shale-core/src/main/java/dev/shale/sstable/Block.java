package dev.shale.sstable;

import dev.shale.CorruptionException;
import dev.shale.KeyComparator;
import dev.shale.internal.annotations.Immutable;
import dev.shale.internal.coding.LittleEndian;
import dev.shale.internal.coding.Varints;
import java.util.Arrays;

/**
 * A parsed, read-only block (format.md §2): the entry region followed by the restart array and
 * count. Its {@link Iterator} reconstructs each full key from the shared/non-shared deltas and can
 * {@code seek} to the first key ≥ a target by binary-searching the restart points and then scanning
 * forward at most one restart interval. Comparator-generic — data and index blocks pass an {@code
 * InternalKeyComparator}, the metaindex a bytewise one.
 *
 * <p><b>Threading:</b> the parsed block is immutable and safe to share; each {@link Iterator} is
 * single-threaded. Structural inconsistencies are {@link CorruptionException} (N4); the CRC is
 * verified by the caller that read the block off disk, before this parse.
 *
 * @see <a href="https://github.com/google/leveldb/blob/main/table/block.cc">LevelDB block.cc</a>
 */
@Immutable
final class Block {

  private final byte[] content;
  private final int restartArrayOffset;
  private final int restartCount;

  /**
   * Parses {@code content}, validating its restart structure before trusting it (ADR-0024 §6). The
   * restart array tells a reader where binary search may start and where the entries end, so it is
   * structural authority, not a number to believe after the block's CRC:
   *
   * <ul>
   *   <li>the count is bounded by the block's length <em>before</em> it is multiplied: (1 + count)
   *       · 4 once overflowed {@code int}, so a 24-byte block claimed over a billion restarts and
   *       still parsed (SH-14);
   *   <li>offsets start at 0, strictly increase, and lie inside the entry region;
   *   <li>each points at an entry that shares nothing with the one before ({@code shared == 0}),
   *       which is what makes it a place a search can start.
   * </ul>
   *
   * An empty entry region holds no restarts, or the single restart 0 the writer emits.
   */
  Block(byte[] content) {
    if (content.length < Integer.BYTES) {
      throw new CorruptionException(
          "block too small for a restart count", 0, Integer.BYTES, content.length);
    }
    this.content = content;
    int countOffset = content.length - Integer.BYTES;
    long count = Integer.toUnsignedLong(LittleEndian.getFixed32(content, countOffset));
    long maxCount = countOffset / Integer.BYTES;
    if (count > maxCount) {
      throw new CorruptionException(
          "block restart count exceeds what the block can hold", countOffset, maxCount, count);
    }
    this.restartCount = (int) count;
    this.restartArrayOffset = countOffset - restartCount * Integer.BYTES;
    validateRestarts();
  }

  private void validateRestarts() {
    int previous = -1;
    for (int i = 0; i < restartCount; i++) {
      int offset = restartOffset(i);
      boolean inRegion = offset >= 0 && offset < Math.max(restartArrayOffset, 1);
      boolean ordered = i == 0 ? offset == 0 : offset > previous;
      if (!inRegion || !ordered) {
        throw new CorruptionException(
            "block restart offset out of place",
            restartArrayOffset + i * Integer.BYTES,
            previous,
            offset);
      }
      if (restartArrayOffset > 0 && Varints.getUnsigned32(content, offset).value() != 0) {
        throw new CorruptionException(
            "block restart point at an entry with shared != 0", offset, 0, content[offset]);
      }
      previous = offset;
    }
  }

  Iterator iterator(KeyComparator comparator) {
    return new Iterator(comparator);
  }

  private int restartOffset(int index) {
    return LittleEndian.getFixed32(content, restartArrayOffset + index * Integer.BYTES);
  }

  /** A forward cursor over one block's entries, with {@code seek} over the restart points. */
  final class Iterator {

    private final KeyComparator comparator;
    private int nextEntryOffset;
    private byte[] currentKey = new byte[0];
    private byte[] currentValue = new byte[0];
    private boolean valid;

    private Iterator(KeyComparator comparator) {
      this.comparator = comparator;
    }

    void seekToFirst() {
      seekToRestart(0);
    }

    /** Positions on the first key ≥ {@code target}, or invalid if none. */
    void seek(byte[] target) {
      // Binary-search the restarts for the last one whose first key is < target.
      int left = 0;
      int right = restartCount - 1;
      while (left < right) {
        int mid = (left + right + 1) >>> 1;
        if (comparator.compare(firstKeyAtRestart(mid), target) < 0) {
          left = mid;
        } else {
          right = mid - 1;
        }
      }
      seekToRestart(left);
      while (valid && comparator.compare(currentKey, target) < 0) {
        next();
      }
    }

    boolean valid() {
      return valid;
    }

    void next() {
      parseEntryAt(nextEntryOffset);
    }

    byte[] key() {
      return currentKey;
    }

    byte[] value() {
      return currentValue;
    }

    private void seekToRestart(int index) {
      currentKey = new byte[0];
      if (restartCount == 0) {
        valid = false;
        return;
      }
      parseEntryAt(restartOffset(index));
    }

    private void parseEntryAt(int offset) {
      if (offset >= restartArrayOffset) {
        valid = false;
        return;
      }
      Varints.Decoded shared = Varints.getUnsigned32(content, offset);
      Varints.Decoded nonShared = Varints.getUnsigned32(content, shared.nextOffset());
      Varints.Decoded valueLength = Varints.getUnsigned32(content, nonShared.nextOffset());
      int keyDeltaOffset = valueLength.nextOffset();
      // Lengths stay long until checked against the region, so no sum can wrap (ADR-0024 §6).
      long end = (long) keyDeltaOffset + nonShared.value() + valueLength.value();
      if (shared.value() > currentKey.length || end > restartArrayOffset) {
        throw new CorruptionException(
            "block entry overruns its region", offset, restartArrayOffset, end);
      }
      int sharedLen = (int) shared.value();
      int nonSharedLen = (int) nonShared.value();
      int valueOffset = keyDeltaOffset + nonSharedLen;
      byte[] key = new byte[sharedLen + nonSharedLen];
      System.arraycopy(currentKey, 0, key, 0, sharedLen);
      System.arraycopy(content, keyDeltaOffset, key, sharedLen, nonSharedLen);
      currentKey = key;
      currentValue = Arrays.copyOfRange(content, valueOffset, (int) end);
      nextEntryOffset = (int) end;
      valid = true;
    }

    /** The full key at restart {@code index}; a restart entry has {@code shared == 0}. */
    private byte[] firstKeyAtRestart(int index) {
      int offset = restartOffset(index);
      Varints.Decoded shared = Varints.getUnsigned32(content, offset);
      Varints.Decoded nonShared = Varints.getUnsigned32(content, shared.nextOffset());
      Varints.Decoded valueLength = Varints.getUnsigned32(content, nonShared.nextOffset());
      int keyDeltaOffset = valueLength.nextOffset();
      if (nonShared.value() > restartArrayOffset - keyDeltaOffset) {
        throw new CorruptionException(
            "block restart key overruns its region", offset, restartArrayOffset, nonShared.value());
      }
      return Arrays.copyOfRange(content, keyDeltaOffset, keyDeltaOffset + (int) nonShared.value());
    }
  }
}
