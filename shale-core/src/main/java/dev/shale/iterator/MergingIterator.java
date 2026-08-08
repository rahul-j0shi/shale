package dev.shale.iterator;

import dev.shale.ByteRange;
import dev.shale.KeyComparator;
import dev.shale.internal.annotations.NotThreadSafe;
import java.util.ArrayList;
import java.util.List;
import java.util.PriorityQueue;
import java.util.function.Consumer;

/**
 * Merges any number of {@link InternalIterator}s into one ascending internal-key stream, using a
 * min-heap keyed on the entry each child is currently positioned at (ADR-0011).
 *
 * <p>The heap holds one slot per <em>non-exhausted</em> child, so advancing costs {@code O(log n)}
 * in the number of live sources rather than {@code O(n)}: pop the smallest, step that child, and
 * push it back if it still has entries. A child that runs dry simply is not pushed back, and the
 * heap shrinks.
 *
 * <p><b>It merges; it does not interpret.</b> Tombstones pass through, superseded versions pass
 * through, and nothing is deduplicated. Reconciliation lives in {@link ReconcilingCursor}. The
 * split is what lets compaction (M6) reuse this class unchanged while making the opposite decision
 * about what to keep — a compaction must retain tombstones and older versions that a reader would
 * never see, and would be actively broken by a merge that dropped them.
 *
 * <p><b>On ties.</b> A sequence number is unique per mutation, so two children can never present
 * the same internal key and the comparator's tie-break is unreachable in a well-formed database. It
 * is still defined — by child index — because a heap whose comparator returns 0 for distinct
 * elements would order them arbitrarily, and "arbitrary but deterministic" is the difference
 * between a reproducible bug and a heisenbug. Note that the tie-break carries no precedence
 * meaning: precedence is already encoded in the sequence number inside the key.
 *
 * <p><b>Why {@code java.util.PriorityQueue} and not a hand-written heap.</b> N1 requires the
 * project's <em>subjects</em> to be hand-written, and lists them: skiplists and concurrent sorted
 * maps, bloom filters, serialisation frameworks, compression codecs, Raft, caches, B-trees. A
 * priority queue is on none of them, and CLAUDE.md explicitly permits JDK structures that are not a
 * project subject. The thing being learned here is merge-iteration — the seek-per-source, the
 * pop-step-push cycle, the reconciliation rule above it — all of which is written out. ADR-0011
 * records this as a judgment call about where N1's boundary falls, and swapping in a hand-written
 * binary heap later is contained entirely within this class.
 *
 * <p><b>Ownership:</b> the merge owns its children and closes all of them in {@link #close()}, even
 * if one throws on the way. It does not own whatever the children read from — the SSTable
 * references belong to the cursor above (N6).
 *
 * <p><b>Threading:</b> {@code @NotThreadSafe}; one owning thread, like its children.
 *
 * @see <a href="https://github.com/google/leveldb/blob/main/table/merger.cc">LevelDB merger.cc</a>
 * @see "Petrov, Database Internals, ch. 7 — merge-iteration"
 */
@NotThreadSafe
public final class MergingIterator implements InternalIterator {

  private final List<Child> children;

  /** Non-exhausted children, smallest current key first. Repopulated by every seek. */
  private final PriorityQueue<Child> heap;

  /**
   * Merges {@code children} under {@code ordering}. Takes ownership: {@link #close()} closes them.
   *
   * @param children the sources to merge, in the order used to break (unreachable) ties
   * @param ordering the internal-key ordering every child is already sorted by
   */
  public MergingIterator(List<InternalIterator> children, KeyComparator ordering) {
    List<Child> wrapped = new ArrayList<>(children.size());
    for (int i = 0; i < children.size(); i++) {
      wrapped.add(new Child(children.get(i), i));
    }
    this.children = List.copyOf(wrapped);
    this.heap =
        new PriorityQueue<>(
            Math.max(1, this.children.size()),
            (a, b) -> {
              int keyOrder =
                  ordering.compare(
                      ByteRange.of(a.iterator.internalKey()),
                      ByteRange.of(b.iterator.internalKey()));
              return keyOrder != 0 ? keyOrder : Integer.compare(a.index, b.index);
            });
  }

  @Override
  public void seek(byte[] internalKey) {
    reposition(child -> child.seek(internalKey));
  }

  @Override
  public void seekToFirst() {
    reposition(InternalIterator::seekToFirst);
  }

  @Override
  public boolean valid() {
    return !heap.isEmpty();
  }

  @Override
  public void next() {
    // Pop before advancing: a PriorityQueue may not have an element's priority change underneath
    // it, and stepping the child is exactly such a change.
    Child smallest = heap.poll();
    smallest.iterator.next();
    if (smallest.iterator.valid()) {
      heap.add(smallest);
    }
  }

  @Override
  public byte[] internalKey() {
    return heap.peek().iterator.internalKey();
  }

  @Override
  public byte[] value() {
    return heap.peek().iterator.value();
  }

  @Override
  public void close() {
    heap.clear();
    for (Child child : children) {
      // No catch-and-continue: closing a child cannot fail (in-memory iterators drop a reference,
      // the SSTable's releases nothing of its own), so a throw here is a bug and must surface
      // rather than be aggregated into a suppressed-exception pile that hides which child broke.
      child.iterator.close();
    }
  }

  /** Repositions every child, then rebuilds the heap from those that landed on an entry. */
  private void reposition(Consumer<InternalIterator> position) {
    heap.clear();
    for (Child child : children) {
      position.accept(child.iterator);
      if (child.iterator.valid()) {
        heap.add(child);
      }
    }
  }

  /** A child and its position in the merge, the latter only for the deterministic tie-break. */
  private record Child(InternalIterator iterator, int index) {}
}
