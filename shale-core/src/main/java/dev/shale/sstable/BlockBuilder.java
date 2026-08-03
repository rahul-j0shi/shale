package dev.shale.sstable;

import dev.shale.internal.annotations.NotThreadSafe;
import dev.shale.internal.coding.LittleEndian;
import dev.shale.internal.coding.Varints;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds one block's content (format.md §2): entries prefix-compressed against the previous key,
 * with a full key stored at every {@code restartInterval}-th entry (a restart point) so a reader
 * can binary-search. Keys must be added in ascending order. The 5-byte block trailer (type + CRC)
 * is the writer's responsibility, not the builder's.
 *
 * <p><b>Threading:</b> not thread-safe; used by a single {@code SSTableWriter}.
 *
 * @see <a href="https://github.com/google/leveldb/blob/main/table/block_builder.cc">LevelDB
 *     block_builder.cc</a>
 */
@NotThreadSafe
final class BlockBuilder {

  private final int restartInterval;
  private final ByteArrayOutputStream content = new ByteArrayOutputStream();
  private final List<Integer> restarts = new ArrayList<>();
  private byte[] lastKey = new byte[0];
  private int sinceRestart;

  BlockBuilder(int restartInterval) {
    this.restartInterval = restartInterval;
    restarts.add(0); // the first entry is always a restart
  }

  /** Appends {@code key → value}; {@code key} must be ≥ every key added so far. */
  void add(byte[] key, byte[] value) {
    int shared = 0;
    if (sinceRestart < restartInterval) {
      int limit = Math.min(lastKey.length, key.length);
      while (shared < limit && lastKey[shared] == key[shared]) {
        shared++;
      }
    } else {
      restarts.add(content.size()); // start a new restart run with a full key
      sinceRestart = 0;
    }
    int nonShared = key.length - shared;

    writeVarint32(shared);
    writeVarint32(nonShared);
    writeVarint32(value.length);
    content.write(key, shared, nonShared);
    content.write(value, 0, value.length);

    lastKey = key.clone();
    sinceRestart++;
  }

  /** True until the first {@link #add}. */
  boolean isEmpty() {
    return content.size() == 0;
  }

  /** A conservative encoded-size estimate, for deciding when a data block is full. */
  int currentSizeBytes() {
    return content.size() + (restarts.size() + 1) * Integer.BYTES;
  }

  /** Appends the restart array and count, and returns the finished block content. */
  byte[] finish() {
    for (int restart : restarts) {
      writeFixed32(restart);
    }
    writeFixed32(restarts.size());
    return content.toByteArray();
  }

  private void writeVarint32(int value) {
    byte[] encoded = new byte[Varints.size(value)];
    Varints.put(encoded, 0, value);
    content.writeBytes(encoded);
  }

  private void writeFixed32(int value) {
    byte[] encoded = new byte[Integer.BYTES];
    LittleEndian.putFixed32(encoded, 0, value);
    content.writeBytes(encoded);
  }
}
