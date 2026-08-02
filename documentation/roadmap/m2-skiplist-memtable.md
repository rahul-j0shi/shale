# M2 — MemTable + immutable handoff: implementation plan

**Goal:** replace the M1 `TreeMemtable` with a **hand-written skiplist** (ADR-0009), add **memory
accounting**, and add **memtable switching** — the active → immutable handoff — so that the engine's
reads stop blocking its writes. The skiplist is a single-writer, lock-free-reader structure (LevelDB
`skiplist.h`); the engine holds the live memtable set in one `volatile` snapshot so readers never take
a lock.

**Depends on:** M1 (`Memtable` seam, `Shale` engine, WAL writer/reader, `InternalKey` stack, `Clock`,
`Metrics`).

**Design locked in:** ADR-0009 (skiplist concurrency + on-heap nodes). No on-disk format change — the
WAL format (ADR-0007) is untouched; a switch only *rolls* to a new segment using the existing machinery.

## Scope

**In M2:**
- `SkiplistMemtable` — hand-written skiplist behind the unchanged `Memtable` seam; `@ThreadSafe`
  (one writer at a time, lock-free readers); on-heap nodes; LevelDB tower-height RNG (branch 4, max
  height 12), seedable for deterministic tests. Cites `skiplist.h` (N9).
- Memory accounting: `sizeBytes()` already exists; add a `writeBufferSizeBytes` threshold knob on the
  engine (default `DEFAULT_WRITE_BUFFER_SIZE_BYTES`), overriding `open`.
- Memtable switching in the engine: when `active.sizeBytes()` crosses the threshold after a write,
  freeze the active memtable into an immutable list, **roll a new WAL segment**, create a new active
  memtable, and publish the new state as one atomic `volatile` write.
- Read path rework: reads take a single volatile read of the `{active, immutables}` snapshot and query
  each lock-free skiplist (newest first), with no lock; writers hold a dedicated write lock. Preserves
  D3 ordering and every M1 behaviour.
- Recovery: replay **all** present segments in order into a single active memtable (immutables collapse
  on reopen — logically identical, since sequence order is preserved). Open a fresh segment after replay.
- Metrics: `memtable.switch.count`, `memtable.size.bytes` (gauge), `memtable.immutable.count` (gauge).
- `TreeMemtable` retained as the differential-test oracle.

**Deferred:** flushing an immutable memtable to an SSTable and deleting its WAL segment (M3 — this is
what drains the immutable list and bounds memory); arena / off-heap nodes (a later `perf/` branch,
gated on a JMH result); the heap-based multi-way merge iterator across SSTables (M4 — M2's cross-memtable
read is a small, in-memory merge over active + immutables only); `WriteBatch` / snapshots (M7).

## Task order (TDD; each task one commit, gate green)

1. **Design docs** — ADR-0009, M2 plan, ADR index. *(this commit)*
2. **SkiplistMemtable — structure & single-thread behaviour.** Node, tower-height RNG (seeded), `add`,
   `ceiling`, `entries`, `sizeBytes`. Tests: ordering by `InternalKeyComparator`, newest-version-first,
   tombstone entry preserved, duplicate internal keys, empty list, `ceiling` boundaries. Red → green
   each behaviour.
3. **Differential property test** — `SkiplistMemtablePropertyTest` (jqwik): random `add`/`ceiling`
   sequences must agree with a `TreeMemtable` oracle on `ceiling` and on `entries` order. Shrinks to a
   minimal divergence if the skiplist is wrong.
4. **Concurrency stress test** — `SkiplistMemtableConcurrencyTest`: one writer inserting a known key
   set while N readers traverse concurrently; assert readers never throw, never observe a malformed or
   out-of-order entry, and that after the writer joins a fresh scan sees every key exactly once. No
   `Thread.sleep` (N8) — coordinate with `CountDownLatch`/`CyclicBarrier` and a fixed op count.
5. **Engine read-path rework** — introduce the `volatile` `{active, immutables}` snapshot and a write
   lock; move reads off the monitor onto the snapshot; swap the engine's memtable to `SkiplistMemtable`.
   No switching yet (immutables always empty). All M1 `ShaleTest`/crash/model tests stay green — this is
   a behaviour-preserving concurrency refactor guarded by the existing suite.
6. **Memory accounting + switch** — add `writeBufferSizeBytes` + `open` override; after a write, switch
   when the threshold is crossed (freeze active, roll WAL segment, new active, publish state). Tests:
   a small buffer forces a switch after K writes (assert `memtable.switch.count` and immutable count);
   values written before and after a switch all read back correctly across memtables.
7. **Recovery across switches** — reopen after one or more switches recovers every acknowledged write
   (all segments replayed in order). Extend the crash test / add a reopen test that switches first.
8. **Metrics + package-info/glossary docs** — emit the three memtable metrics; update
   `memtable/package-info.java` (now `@ThreadSafe`, lock-free readers) and `dev.shale/package-info.java`
   (write lock + reader snapshot, lock ordering); add any new vocabulary to `naming.md` in this commit
   (naming §1: "add it here in the same commit").
9. **As-built architecture doc** — `documentation/architecture/m2-memtable-and-handoff.md`: skiplist
   structure + insert, the lock-free read, the active→immutable handoff and volatile publication, WAL
   roll, recovery collapse. Mermaid, validated with mermaid-cli. Index it.
10. **Model harness** — drive switching in the model test (small write buffer) and add a `restart` op
    that reopens the engine mid-sequence and re-checks against the oracle (testing.md §1).
11. **Finish** — full `./gradlew build` + `crashTest` green; release note; tag `m2-skiplist`.

## Invariants to hold (checked by tests, not just prose)

- **Skiplist ≡ balanced tree:** `SkiplistMemtable` and `TreeMemtable` agree on `ceiling`/`entries` for
  every operation sequence (task 3).
- **Safe publication:** a lock-free reader that observes a node observes its fully-initialised key and
  value; concurrent readers never throw or see a torn/out-of-order entry (task 4).
- **D3 unchanged:** WAL append (+force for SYNC/GROUP) still precedes the memtable update, which
  precedes acknowledgement — the read-path rework must not touch the write ordering.
- **Switch correctness:** reads return the newest version of a key regardless of which memtable (active
  or any immutable) holds it; a switch never loses or reorders a write.
- **Recovery:** reopening after any number of switches yields exactly the acknowledged writes, in the
  same logical state as before the crash.
- **N8:** no `Thread.sleep` anywhere in the concurrency tests.
