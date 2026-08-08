package dev.shale.sstable;

import dev.shale.KeyComparator;
import dev.shale.internal.annotations.NotThreadSafe;
import dev.shale.iterator.InternalIterator;
import java.util.function.Function;

/**
 * A cursor over one SSTable's entries, descending the index block into data blocks and opening each
 * data block only when iteration reaches it (format.md §1, ADR-0011).
 *
 * <p>This is LevelDB's {@code TwoLevelIterator}, and the two levels are the reason an SSTable can
 * be read without holding it in memory: the index block — one entry per data block, mapping that
 * block's last key to its {@code BlockHandle} — is small enough to keep resident, while the data
 * blocks it points at are fetched one at a time. Iterating a 100 MB table therefore costs one block
 * of memory, not 100 MB, which is precisely what the materialising {@code entries()} this replaced
 * could not do.
 *
 * <p><b>Why the index is searched by <i>last</i> key.</b> The index entry for a block records the
 * last key it contains, so the first index entry whose key is ≥ a target names the only block that
 * can hold that target. Seeking is thus one binary search in the index followed by one binary
 * search within a single data block.
 *
 * <p>A seek that lands past the end of the block the index chose must fall through to the next
 * block rather than report exhaustion: the index says "the target is not after this block", which
 * does not guarantee the block contains an entry ≥ the target once the target sorts after every key
 * in it. The same fall-through drives ordinary advance, so one loop serves both.
 *
 * <p><b>Threading:</b> {@code @NotThreadSafe} — one owning thread. The reader underneath is
 * thread-safe and may back many iterators at once. <b>Errors:</b> a data block that fails its CRC
 * or is structurally inconsistent raises {@code CorruptionException} out of {@link #next} or {@link
 * #seek}; the iterator never skips a damaged block to keep going (N4).
 *
 * @see <a href="https://github.com/google/leveldb/blob/main/table/two_level_iterator.cc">LevelDB
 *     two_level_iterator.cc</a>
 */
@NotThreadSafe
final class SSTableIterator implements InternalIterator {

  private final KeyComparator comparator;
  private final Block indexBlock;

  /** Loads and CRC-verifies the data block a handle points at. Owned by the reader. */
  private final Function<BlockHandle, Block> dataBlockLoader;

  /** Position in the index: which data block is open. Single-thread owned. */
  private Block.Iterator index;

  /** Position within the open data block, or null when unpositioned or exhausted. */
  private Block.Iterator data;

  SSTableIterator(
      KeyComparator comparator, Block indexBlock, Function<BlockHandle, Block> dataBlockLoader) {
    this.comparator = comparator;
    this.indexBlock = indexBlock;
    this.dataBlockLoader = dataBlockLoader;
  }

  @Override
  public void seek(byte[] internalKey) {
    index = indexBlock.iterator(comparator);
    index.seek(internalKey);
    if (!index.valid()) {
      data = null; // past the last block's last key: no entry can be ≥ the target
      return;
    }
    openDataBlock();
    data.seek(internalKey);
    skipToNextNonEmptyBlock(); // the chosen block may end before the target
  }

  @Override
  public void seekToFirst() {
    index = indexBlock.iterator(comparator);
    index.seekToFirst();
    if (!index.valid()) {
      data = null; // an empty table has no data blocks
      return;
    }
    openDataBlock();
    data.seekToFirst();
    skipToNextNonEmptyBlock(); // tolerate an empty leading block rather than assume none exists
  }

  @Override
  public boolean valid() {
    return data != null && data.valid();
  }

  @Override
  public void next() {
    data.next();
    skipToNextNonEmptyBlock();
  }

  @Override
  public byte[] internalKey() {
    return data.key();
  }

  @Override
  public byte[] value() {
    return data.value();
  }

  @Override
  public void close() {
    // Nothing to release: blocks are plain heap arrays and the FileChannel belongs to the
    // SSTableReader, whose reference count the *cursor* holds (ADR-0011), not this iterator.
    index = null;
    data = null;
  }

  /**
   * Advances into following data blocks until one yields an entry, or the table is exhausted. Also
   * covers the ordinary case where the current block simply ran out mid-iteration.
   */
  private void skipToNextNonEmptyBlock() {
    while (data != null && !data.valid()) {
      index.next();
      if (!index.valid()) {
        data = null;
        return;
      }
      openDataBlock();
      data.seekToFirst();
    }
  }

  private void openDataBlock() {
    BlockHandle handle = BlockHandle.decode(index.value(), 0).handle();
    data = dataBlockLoader.apply(handle).iterator(comparator);
  }
}
