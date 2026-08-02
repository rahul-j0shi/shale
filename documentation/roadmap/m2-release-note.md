# M2 — MemTable + immutable handoff (release note)

**Tag:** `m2-skiplist` · **Status:** complete, `./gradlew build` + `crashTest` green on JDK 25.

## Delivered

The memtable is now a hand-written lock-free skiplist, and reads no longer block writes.

- **Skiplist (`dev.shale.memtable.SkiplistMemtable`, ADR-0009):** a from-scratch skiplist behind
  the unchanged `Memtable` seam, following LevelDB `db/skiplist.h` — one serialised writer, any
  number of lock-free readers. Forward pointers are `AtomicReferenceArray` slots published with
  `setRelease` and read with `getAcquire`, so a reader that observes a node observes its final key
  and value and never a torn node. Tower heights use LevelDB's branch-4 / max-height-12 scheme from
  a seeded RNG. Nodes are on-heap; the arena representation is a later, benchmark-justified change.
- **Lock-free reads (`dev.shale.Shale`):** writers serialise on a private `writeLock` guarding the
  WAL, the sequence counter, and publication of the memtable set; readers take one `volatile` read
  of the set and traverse without a lock (the immutable-snapshot-over-locks pattern).
- **Memory accounting + switch:** a `writeBufferSizeBytes` knob (default 4 MiB, LevelDB's
  `write_buffer_size`). When the active memtable fills, the engine freezes it into the immutable
  list, rolls to a new WAL segment, and publishes a new memtable set in one volatile write. Emits
  `memtable.switch.count` and the size / immutable-count gauges.
- **Cross-memtable reads:** point reads scan newest memtable first (the sequence-boundary property
  makes the first hit the newest version); range scans merge the whole set, newest-per-key, dropping
  tombstones. Recovery replays every segment in order into one memtable — the runtime split re-forms
  as writes cross the threshold again.
- **`TreeMemtable`** is retained as the differential-test oracle.

## Exit criteria (met)

- `./gradlew build` green; `crashTest` green; `shale-core` still has zero runtime deps (N1).
- **Skiplist ≡ balanced tree:** a jqwik differential test asserts identical `entries()`/`ceiling()`
  against a `TreeMap` oracle over random operation sequences.
- **Lock-free safety:** a stress test runs four readers against a live writer and asserts no torn or
  out-of-order entry is ever observed; stable across repeated runs, no `Thread.sleep` (N8).
- **Switch + recovery:** the engine model test drives the real engine with a 256-byte buffer
  (constant switching) and a restart every 700 ops, diffing against the oracle throughout.

## Notes for the next milestone

M3 writes an immutable memtable to an **SSTable** (data blocks, restart points, block index, footer)
and deletes the covering WAL segment after the flush — which is what finally *drains* the immutable
list M2 leaves accumulating in memory. The arena-backed skiplist and true leader/follower group
commit remain deferred (both gated on a benchmark). Reads then check the memtable set plus one
SSTable, setting up the multi-way merge at M4.
