package dev.shale;

import dev.shale.memtable.Memtable;
import dev.shale.memtable.SkiplistMemtable;
import dev.shale.memtable.TreeMemtable;

/**
 * Which memtable the engine builds. The engine uses the skiplist (ADR-0009); the tree memtable is
 * its reference implementation, kept so the metamorphic test can run one history on both and
 * require the same answers (M5 Step 8). Package-private: not a tuning knob.
 */
enum MemtableKind {
  SKIPLIST {
    @Override
    Memtable create(KeyComparator comparator) {
      return new SkiplistMemtable(comparator, Shale.SKIPLIST_SEED);
    }
  },
  TREE {
    @Override
    Memtable create(KeyComparator comparator) {
      return new TreeMemtable(comparator);
    }
  };

  abstract Memtable create(KeyComparator comparator);
}
