package dev.shale.memtable;

import dev.shale.iterator.InternalIterator;
import java.util.ArrayList;
import java.util.List;

/**
 * Test helper: drains a memtable's {@link InternalIterator} into the list that {@code entries()}
 * used to return, before M4 removed it (ADR-0011).
 *
 * <p>The suites that assert on whole-memtable state — ordering, the skiplist-vs-tree differential,
 * and the concurrency invariant that every node is intact — are clearer against a list than against
 * an advance loop, and none of them is large enough for materialising to matter. What they must
 * <i>not</i> do is tempt production code back into the same shape, which is why this is here and
 * not on the interface.
 */
final class Memtables {

  private Memtables() {}

  /** Every entry in ascending internal-key order, copied out of the iterator. */
  static List<Memtable.Entry> entries(Memtable memtable) {
    List<Memtable.Entry> out = new ArrayList<>();
    try (InternalIterator iterator = memtable.iterator()) {
      for (iterator.seekToFirst(); iterator.valid(); iterator.next()) {
        out.add(new Memtable.Entry(iterator.internalKey().clone(), iterator.value().clone()));
      }
    }
    return out;
  }
}
