# 0011. Read through one InternalIterator seam, merged by a heap, pinned by the cursor

- **Status:** Accepted
- **Date:** 2026-08-08
- **Milestone:** M4
- **Reversible:** partly — the `InternalIterator` shape and the cursor's reference-holding
  contract are public API in `shale-core` and cost a coordinated change across every source
  to revisit. Which heap backs the merge, and whether `get` later joins the seam, are free.

## Context

M3 left reads correct but structurally provisional. Two stand-ins carry the milestone:

- `SSTableReader.entries()` materialises an entire table into a `List<Entry>` — its Javadoc
  says outright "the streaming merge across tables is M4".
- `Shale.scan` (`Shale.java:305-341`) copies every entry from every memtable and every
  SSTable into one list, sorts it, then discards everything outside the requested range.

The cost is not subtle. A scan of one key in a database of a million pays for a million
decoded entries and an O(n log n) sort, and peak memory is the whole database. The
`fromInclusive` bound — the thing that should drive a `seek` into each source — is applied
as a filter *after* the work is done.

Three forces decide the shape of the fix:

1. **The reconciliation rule is already fixed.** ADR-0004 packs the internal key as user key
   ascending, sequence descending, precisely so that the newest version of a user key sorts
   first and a merge can reconcile by taking the first entry per key. M4 must consume that
   property, not reinvent it.
2. **Sources are heterogeneous but must look identical to the merge.** A skiplist walk, a
   `TreeMap` walk, and a two-level index-block → data-block descent through a file have
   nothing in common except order. The merge must not know which is which.
3. **Streaming changes who owns the files.** A materialising scan copies bytes out and can
   forget the tables. A streaming cursor reads from live `FileChannel`s for as long as the
   caller holds it. N6 requires every file handle to have a named owner, and M5's
   delete-on-zero lifecycle will start deleting tables underneath long-running readers.

What we did not know when M3 shipped: whether the point-lookup path (`get`) should also be
rebuilt on the merge. It should not — see Decision.

## Options considered

### Option A — One `InternalIterator` interface, a heap merge, cursor-pinned tables

Define a single ordered-cursor interface over encoded internal keys (`seek`, `seekToFirst`,
`valid`, `next`, `internalKey`, `value`, `close`). Every source implements it: the skiplist
walks level 0, the `TreeMap` walks its `NavigableMap`, and the SSTable uses a two-level
iterator that descends the index block and opens data blocks lazily. A `MergingIterator`
holds a min-heap of children and yields the global internal-key order. A `ReconcilingCursor`
sits on top applying the three read rules (first-per-user-key wins, tombstones hide, stop at
the upper bound) and holds a `retain()` on every SSTable in its view until `close()`.

This is LevelDB's structure — `Iterator`, `MergingIterator`, `TwoLevelIterator`, `DBIter` —
and the arrangement Petrov ch. 7 describes as merge-iteration plus reconciliation.

### Option B — Keep materialising, but bound it by the scan range

Leave the list-and-sort scan, but push `fromInclusive`/`toExclusive` down so each source only
contributes entries in range. Much smaller diff; fixes the common case of a narrow scan.

Fails on the case that matters: a full or wide scan still materialises everything, so peak
memory stays proportional to the data, not the result. It also leaves no seam for M7's
snapshot filtering or M6's compaction, both of which need to *stream* a merge of many
sources. It buys a milestone of time and pays for it twice.

### Option C — As A, but route `get` through the merge as well

One read path for everything: `get` builds a merge over the same sources, seeks to the lookup
key, takes the first entry. Less code, one place where reconciliation lives.

LevelDB deliberately does not do this — `DBImpl::Get` probes the memtable, then the immutable
memtable, then consults the version's files directly, precisely to avoid constructing and
heapifying a merge for a single-key read.

### Option D — Hand-write the binary heap rather than use `java.util.PriorityQueue`

N1 requires core mechanisms to be hand-written. The question is whether the heap inside the
merge iterator is one of them.

## Decision

**Option A**, with three specifics:

1. **`get` keeps its per-source `ceiling` probe** (rejecting C). The point path stays separate
   and carries a Javadoc note saying why.
2. **The materialising `entries()` methods are removed**, not kept alongside the iterator.
   `Memtable.entries()` and `SSTableReader.entries()` both go; the flush path
   (`Shale.java:254`) and the recovery sequence scan (`Shale.java:377`) move to the iterator
   too, so flush stops materialising a whole memtable as a side effect.
3. **`java.util.PriorityQueue` backs the merge** (rejecting D).

## Rationale

**Why the seam, and why now.** Everything M4 through M7 must do is a merge of ordered sources
that differ only in where their bytes live. Compaction (M6) merges SSTables and writes the
result. Snapshot reads (M7) merge the same sources with a sequence-number filter. Building
those on three bespoke traversals, or on a materialising list, would mean writing the merge
three times and getting the tombstone rule subtly different in each. One interface, one heap,
one reconciliation is the whole architectural content of this milestone.

**Why `get` stays separate.** This is the choice most likely to look inconsistent later, so
the reasoning is worth pinning down. A point lookup does not need a global order — it needs
the first source that has *any* version of one key, and the existing probe returns exactly
that with no heap, no per-source seek, and no allocation beyond the result. Rebuilding it on
the merge would make it measurably slower to buy tidiness, and CLAUDE.md is explicit that
correctness comes before restructuring a path nobody has measured. LevelDB reached the same
conclusion for the same reason. When M7 adds snapshot filtering, the rule lives in one helper
that both paths call; the paths themselves stay distinct.

**Why remove `entries()` instead of keeping both.** Keeping a materialising accessor next to a
streaming one is the "two names for one thing" that N10 exists to prevent, and it is the trap
a future milestone falls into — reaching for the convenient one and silently reintroducing an
unbounded read. The memtable's version is genuinely bounded by the write buffer and so has no
memory hazard, which is a real argument for keeping it; we are removing it anyway, because a
memtable that streams and an SSTable that materialises is exactly the asymmetry that makes
someone write a materialising merge again. Tests that want a list collect from the iterator.

**Why the cursor pins its tables.** N6 admits no cursor that reads a file it does not hold a
reference to. Today nothing deletes an SSTable, so this costs us something and buys us
nothing *yet* — the retain/release machinery on `SSTableReader` has been unexercised since
M3. M5 introduces delete-on-zero, and the alternative is shipping a known-wrong lifetime into
the milestone that will already be busy inverting file discovery onto the manifest. We take
the cost now, while the read path is the thing being rewritten anyway. The consequence is
that `Cursor.close()` stops being optional: a leaked cursor pins file handles, and a test
must prove that closing one drops exactly the references it took.

**Why `PriorityQueue`.** N1 names what must be hand-written: skiplists and concurrent sorted
maps, bloom filters, serialisation frameworks, compression codecs, Raft, caches, B-trees. A
priority queue is on none of those lists, and CLAUDE.md explicitly permits JDK structures that
are "not a project subject (e.g. `ArrayDeque`, `ReentrantLock`)". The roadmap component here
is *the merge iterator* — the reconciliation rule, the seek-per-source, the tombstone
handling — and we are writing all of it. The heap underneath is plumbing in the same category
as the `ArrayDeque` the rule blesses by name. This is a judgment call on where N1's boundary
falls, recorded so it can be argued with: a reader who thinks "heap-based multi-way merge" in
the roadmap names the heap as the subject would be making a coherent case for the opposite,
and swapping in a hand-written binary heap later is a contained change behind
`MergingIterator`.

**A property worth stating.** Sequence numbers are unique per mutation, so two children can
never hold the same internal key. The heap has no meaningful ties. We break them by child
index for determinism and document that the tie-break is unreachable rather than implying it
encodes precedence — precedence is already carried by the sequence number in the key.

## Consequences

**Positive:** a scan costs its result, not the database. `fromInclusive` becomes a real `seek`
into every source. Flush stops materialising the memtable it is writing. M6's compaction and
M7's snapshot reads have the seam they need, and the reconciliation rule exists in exactly one
place. The reference counting written in M3 finally has a consumer.

**Negative:** `Cursor.close()` is now load-bearing — leaking a cursor pins file handles, and
every caller needs try-with-resources. Two public methods are removed from `shale-core`,
touching nine test call sites and two production ones. Two read paths now exist (`get` probes,
`scan` merges) and must be kept semantically identical; when M7 adds snapshot filtering, both
must learn it.

**Neutral:** `dev.shale.sstable` gains a dependency on `dev.shale.iterator` (one direction
only) because the two-level iterator needs package-private access to `Block`. The M1
`TreeMemtable` gets an iterator it will likely never use in production.

**If we need to reverse this:** the `InternalIterator` shape is public API, so changing it is a
coordinated edit across four implementations and their tests — unpleasant but mechanical, and
no on-disk bytes are involved. The cursor's retain contract is the harder one to unwind: by
M5 the manifest's delete-on-zero will depend on it, at which point removing it reintroduces
use-after-delete. Reverting to a materialising scan is always available as an escape hatch and
costs only performance.

## References

- LevelDB `table/merger.cc` (`MergingIterator`), `table/two_level_iterator.cc`, `db/db_iter.cc`
  (reconciliation and tombstone handling), `db/db_impl.cc` `DBImpl::Get` (the separate point
  path this ADR preserves).
- Petrov, *Database Internals*, ch. 7 — merge-iteration and reconciliation in log-structured
  storage.
- ADR-0004 (internal key encoding — the user-asc/sequence-desc order this merge consumes).
- ADR-0010 (SSTable block table — the index/data block structure the two-level iterator walks).
- `documentation/roadmap/shale-roadmap.md` §E, M4.
