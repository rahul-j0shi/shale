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

  Block(byte[] content) {
    if (content.length < Integer.BYTES) {
      throw new CorruptionException(
          "block too small for a restart count", 0, Integer.BYTES, content.length);
    }
    this.content = content;
    this.restartCount = LittleEndian.getFixed32(content, content.length - Integer.BYTES);
    this.restartArrayOffset = content.length - (1 + restartCount) * Integer.BYTES;
    if (restartCount < 0 || restartArrayOffset < 0) {
      throw new CorruptionException(
          "restart array overruns block", content.length, content.length, restartCount);
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
      Varints.Decoded shared = Varints.get(content, offset);
      Varints.Decoded nonShared = Varints.get(content, shared.nextOffset());
      Varints.Decoded valueLength = Varints.get(content, nonShared.nextOffset());
      int sharedLen = (int) shared.value();
      int nonSharedLen = (int) nonShared.value();
      int valueLen = (int) valueLength.value();
      int keyDeltaOffset = valueLength.nextOffset();
      int valueOffset = keyDeltaOffset + nonSharedLen;
      int end = valueOffset + valueLen;
      if (sharedLen > currentKey.length || end > restartArrayOffset) {
        throw new CorruptionException(
            "block entry overruns its region", offset, restartArrayOffset, end);
      }
      byte[] key = new byte[sharedLen + nonSharedLen];
      System.arraycopy(currentKey, 0, key, 0, sharedLen);
      System.arraycopy(content, keyDeltaOffset, key, sharedLen, nonSharedLen);
      currentKey = key;
      currentValue = Arrays.copyOfRange(content, valueOffset, end);
      nextEntryOffset = end;
      valid = true;
    }

    /** The full key at restart {@code index}; a restart entry has {@code shared == 0}. */
    private byte[] firstKeyAtRestart(int index) {
      int offset = restartOffset(index);
      Varints.Decoded shared = Varints.get(content, offset);
      Varints.Decoded nonShared = Varints.get(content, shared.nextOffset());
      Varints.Decoded valueLength = Varints.get(content, nonShared.nextOffset());
      int keyDeltaOffset = valueLength.nextOffset();
      return Arrays.copyOfRange(content, keyDeltaOffset, keyDeltaOffset + (int) nonShared.value());
    }
  }
}
