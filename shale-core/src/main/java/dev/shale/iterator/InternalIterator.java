package dev.shale.iterator;

import dev.shale.CorruptionException;
import dev.shale.internal.annotations.NotThreadSafe;

/**
 * A forward cursor over encoded <b>internal</b> keys in ascending {@code InternalKeyComparator}
 * order — user key ascending, sequence number descending (ADR-0004). Every read source implements
 * it: a memtable, an SSTable, and the {@link MergingIterator} that merges them. The merge is
 * therefore blind to where a source's bytes live, which is the entire point of the seam (ADR-0011).
 *
 * <p>Because sequence descends within a user key, the <b>first</b> entry a source yields for a user
 * key is that source's newest version of it. Reconciliation across sources is exactly the same rule
 * applied to the merged stream, and lives in {@link ReconcilingCursor} rather than here — an
 * iterator reports what is stored, it does not decide what is visible.
 *
 * <p><b>Lifecycle.</b> An iterator is positioned by {@link #seek} or {@link #seekToFirst} before it
 * is read; a freshly constructed iterator is not positioned and {@link #valid()} is false. {@link
 * #next()} may only be called while valid. {@link #internalKey()} and {@link #value()} may only be
 * called while valid, and the arrays they return are owned by the iterator: a caller that needs to
 * retain bytes past the next {@code next()} must copy them.
 *
 * <p><b>Threading:</b> {@code @NotThreadSafe} — owned by the thread that created it. Note that this
 * says nothing about the underlying source, which may well be shared: a {@code SkiplistMemtable} is
 * safe for concurrent readers (ADR-0009) and an {@code SSTableReader} is thread-safe, but each
 * <i>iterator</i> over them carries mutable position state for one thread.
 *
 * <p><b>Errors:</b> a source backed by a file may throw {@link CorruptionException} from {@link
 * #next()} or {@link #seek} when a block fails its checksum or is structurally inconsistent. The
 * exception propagates; an iterator never skips a damaged region to keep going (N4).
 *
 * @see <a href="https://github.com/google/leveldb/blob/main/include/leveldb/iterator.h">LevelDB
 *     iterator.h</a> — the same role, minus the reverse-iteration half we do not need until range
 *     scans grow a backward direction.
 */
@NotThreadSafe
public interface InternalIterator extends AutoCloseable {

  /** Positions on the first entry whose internal key is ≥ {@code internalKey}, if any. */
  void seek(byte[] internalKey);

  /** Positions on the first entry in the source, if any. */
  void seekToFirst();

  /** True while positioned on an entry; false before positioning and once exhausted. */
  boolean valid();

  /** Advances to the next entry. Only legal while {@link #valid()}. */
  void next();

  /**
   * The current entry's encoded internal key. Only legal while {@link #valid()}. Owned by the
   * iterator — copy before the next {@link #next()} if the bytes must outlive it.
   */
  byte[] internalKey();

  /**
   * The current entry's value, empty for a tombstone. Only legal while {@link #valid()}. Owned by
   * the iterator — copy before the next {@link #next()} if the bytes must outlive it.
   */
  byte[] value();

  /**
   * Releases whatever the iterator holds. In-memory sources have nothing to release and override
   * this as a no-op; file-backed ones may not. Always safe to call more than once.
   */
  @Override
  void close();
}
