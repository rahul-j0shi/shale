/**
 * Merge-iteration and reconciliation: how a read spanning many ordered sources becomes one answer.
 *
 * <p>An LSM read is a merge by construction. A user key's versions are scattered across the active
 * memtable, any immutable memtables awaiting flush, and every SSTable — and the newest version wins
 * wherever it happens to live. This package holds the three pieces that resolve that (ADR-0011):
 *
 * <ul>
 *   <li>{@link dev.shale.iterator.InternalIterator} — the seam. One forward cursor over encoded
 *       internal keys, implemented by every source, so the merge cannot tell a skiplist walk from a
 *       descent through an SSTable's index and data blocks.
 *   <li>{@link dev.shale.iterator.MergingIterator} — a min-heap over child iterators yielding the
 *       global internal-key order. It merges; it does not interpret.
 *   <li>{@link dev.shale.iterator.ReconcilingCursor} — the interpretation. Newest version per user
 *       key wins, tombstones hide the key, and the scan stops at its upper bound.
 * </ul>
 *
 * <p><b>Why the split.</b> Merging and reconciling are separable, and separating them is what makes
 * the later milestones cheap: compaction (M6) merges the same way but keeps tombstones and older
 * versions that are still needed, and snapshot reads (M7) reconcile the same way but ignore
 * sequences above the snapshot. Both reuse the heap untouched and vary only the layer above it.
 *
 * <p><b>The ordering this all rests on.</b> ADR-0004 packs the internal key so that user keys
 * ascend and, within a user key, sequence numbers descend. Merge order therefore delivers a user
 * key's newest version first, and reconciliation is "take the first, skip the rest" — no per-source
 * precedence logic anywhere. Sequence numbers are unique per mutation, so two sources can never
 * present the same internal key and the heap has no meaningful ties.
 *
 * <p><b>Threading:</b> every type here is {@code @NotThreadSafe} and owned by the thread that
 * created it, even though the sources underneath are safe to share. <b>Resources:</b> a {@code
 * ReconcilingCursor} holds a reference to each SSTable it can read from for its whole life and
 * drops them on {@code close()} (N6) — closing it is mandatory, not housekeeping.
 *
 * <p><b>Entry point:</b> {@link dev.shale.iterator.InternalIterator}.
 *
 * @see <a href="https://github.com/google/leveldb/blob/main/table/merger.cc">LevelDB merger.cc</a>
 * @see <a href="https://github.com/google/leveldb/blob/main/db/db_iter.cc">LevelDB db_iter.cc</a>
 */
package dev.shale.iterator;
