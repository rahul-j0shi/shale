# 0009. A hand-written skiplist memtable: single writer, lock-free readers, on-heap

- **Status:** Accepted
- **Date:** 2026-08-02
- **Milestone:** M2
- **Reversible:** partly — the `Memtable` seam (ADR-0006 / M1) is unchanged, so the *structure* behind it is swappable; but the single-writer/lock-free-reader **concurrency contract** and the on-heap node representation are baked into `SkiplistMemtable` itself and into the engine's read path. On-heap → off-heap (arena) is a clean later swap (see Consequences).

## Context

M1 left the memtable as a `TreeMemtable` — a `TreeMap` behind the `Memtable` seam — explicitly
marked "correctness-first; the concurrent skiplist arrives at M2." Its `package-info` records the
limitation the milestone must remove: *"implementations are single-threaded; the engine serialises
access,"* and `Shale`'s Javadoc says *"Reads block writes at M1 — the immutable-memtable snapshot
that removes that comes with M2."*

M2 must deliver three things the roadmap names: **replace the map with a hand-written skiplist**, add
**memory accounting**, and add **memtable switching** (the active → immutable handoff). Two of these
choices are hard to reverse and are what this ADR pins:

1. **The skiplist's concurrency model** — this sets the memtable package's threading contract and
   dictates how the engine's read path is written.
2. **How nodes are stored** — on-heap Java objects vs. off-heap arena-backed segments.

The driving forces are the project's learning objective (N1/N2: the skiplist is a *subject*, hand-written
and cited, not a library) and the concurrency rule that the engine's hot write path must be short and
readers should not block on writers (concurrency-and-resources.md §2: "prefer immutable snapshots over
locks"). A secondary force is CLAUDE.md §5: "prefer the obvious implementation first… no optimisation
without a committed benchmark."

Note that `ConcurrentSkipListMap` exists in the JDK and would satisfy the interface — but N1 bans a
third-party (or JDK) implementation of a core concept, and the skiplist is the canonical core concept
of this milestone. It must be hand-written (ADR-0002).

## Options considered

### Concurrency model

**A1 — Single-threaded skiplist, engine serialises everything.** The skiplist is `@NotThreadSafe`,
every engine operation (read and write) takes one monitor, exactly as `TreeMemtable` works today. The
"switch" becomes purely structural: reads still block writes, so the milestone's own stated goal is not
met until a later milestone introduces concurrency. Lowest complexity, lowest learning value, and it
leaves the `package-info`'s promise unfulfilled.

**A2 — LevelDB-style: one serialised writer, lock-free readers.** This is the design in LevelDB's
`db/skiplist.h`: a single writer (serialised by the engine) mutates the list; readers traverse it with
no lock, relying on the forward pointers being published safely. A node's `next` pointers are the only
mutable shared state; publishing a node with a release-store and reading pointers with an acquire-load
(in Java: `volatile`/`VarHandle` with `setRelease`/`getAcquire`) guarantees a reader that observes a
node also observes its fully-initialised key and value. Readers may or may not see a concurrent insert,
but never see a torn or partially-linked node. Insertion links levels bottom-up so the list is always a
valid (if possibly stale) structure for a reader. This is the canonical, well-documented design; it is
the actual thing to learn here.

**A3 — Fully concurrent multi-writer skiplist (CAS on every level).** Lock-free for writers too, à la
`ConcurrentSkipListMap` / Fraser-Harris. The engine does not need concurrent writers — the WAL append
already serialises the write path — so this buys nothing the engine uses while adding the hardest
correctness burden in the codebase (ABA, marker nodes for deletion). Out of scope.

### Node storage

**B1 — On-heap nodes.** A node is a small Java object holding the encoded internal key, the value, and
a `next[]` array of forward pointers (one per level). Simple, obviously correct, trivially benchmarkable,
and it lets the concurrency work (A2) proceed without also solving off-heap lifetime. GC pressure from
per-entry objects is real but *unmeasured*.

**B2 — Off-heap arena-backed nodes.** Nodes live in `Arena`-allocated `MemorySegment`s, addressed by
`long` offsets rather than references (the Pebble design). Cuts GC pressure and pointer overhead, but is
substantially more code, pulls the N6 ownership rules (named arena owner, explicit close on flush,
leak detection) forward into M2, and optimises a path nobody has profiled — violating "no optimisation
without a committed benchmark."

## Decision

**A2 + B1.**

- `SkiplistMemtable` is a **hand-written skiplist**, `@ThreadSafe` with the contract: **at most one
  writer at a time** (the engine serialises inserts on its write lock) and **any number of concurrent
  lock-free readers**. It cites LevelDB `db/skiplist.h` (N9) and states where it deviates.
- Forward pointers are published so a reader that observes a node observes its key/value: level-0 link
  with release semantics, higher levels linked after. Readers use acquire-loads. No reader takes a lock.
- **Nodes are on-heap** for M2: `internalKey` and `value` byte arrays plus a `next` array of node
  references. Random tower height uses the LevelDB scheme (branching factor 4, max height 12) from a
  memtable-owned RNG; the RNG is seedable so tests are deterministic.
- The `Memtable` seam (ADR-0006 shape) is **unchanged**: `add` / `ceiling` / `entries` / `sizeBytes` /
  `Entry`. `TreeMemtable` is **retained** — not as dead code but as the differential-test oracle: the
  skiplist must return bit-identical `ceiling`/`entries` results to the `TreeMap`-backed memtable over
  random operation sequences.
- The engine consumes this by holding the live memtable set in **one `volatile` snapshot** (active +
  immutable list). Writers hold a write lock; readers take a single volatile read of the snapshot and
  traverse the lock-free skiplists without any lock — the immutable-snapshot-over-locks pattern
  (concurrency §2). This is what removes "reads block writes."

The off-heap arena representation (B2) is deferred to a **later `perf/` branch**, gated on a JMH result
showing GC cost on a hot workload, per the `perf` rule (commits.md §4).

## Rationale

A2 is chosen over A1 primarily for **learning value, and it is honest to say so** (template's
instruction): the lock-free single-writer skiplist *is* the thing M2 exists to teach, it is the design
every reference engine (LevelDB, RocksDB, HBase) actually ships, and it is what makes the engine's read
path a lock-free snapshot instead of a monitor — the property the roadmap promised. It is only
moderately harder than A1 because the engine already guarantees a single writer, so we inherit the hard
half of concurrency (writer exclusion) for free from the existing write path and only have to get
safe publication right for readers.

B1 over B2 is the straight application of "obvious implementation first, optimise only with a benchmark."
Arena allocation is a genuine optimisation with genuine complexity (N6 lifetimes, flush-time close), and
we have not yet measured that heap allocation is a problem. Doing it now would be optimising blind and
would entangle the concurrency work with off-heap lifetime — two hard things at once. On-heap first keeps
M2 correct and reviewable; the arena becomes a clean, isolated, *measured* follow-up behind the same
seam.

The accepted tradeoff on the RUM triangle is unchanged (the memtable is a write buffer either way); the
tradeoff this ADR actually makes is **complexity now vs. learning + the promised concurrency**: we take
on real safe-publication reasoning (and the concurrency tests that go with it) in exchange for a memtable
that matches how real engines work and a read path that does not serialise behind writes.

## Consequences

**Positive:** reads stop blocking writes (the milestone goal); the memtable matches the canonical
LevelDB design and carries its citation; `TreeMemtable` becomes a high-value differential oracle rather
than dead weight; the `Memtable` seam is proven to admit a second, very different implementation without
changing the engine — validating the ADR-0006 abstraction.

**Negative:** M2 takes on the codebase's first genuine lock-free reasoning and its first
reader/writer concurrency tests (which must be deterministic without `Thread.sleep`, N8). The engine's
read path is rewritten from "one monitor" to "volatile snapshot + write lock," a real structural change
that must preserve the D3 ordering and every M1 test. On-heap nodes leave a known, deliberate GC-pressure
gap that a later benchmark must close.

**Neutral:** without flush (M3) the immutable memtables produced by a switch accumulate in memory — the
switch *mechanism* ships in M2, the *drain* is M3. Under sustained writes with a low buffer size this
grows unbounded; the default buffer size is set so ordinary use does not hit it, and tests drive
switching explicitly with a small buffer. This is the documented M2 → M3 seam, not a defect.

**If we need to reverse this:** the concurrency model and node storage live entirely inside
`SkiplistMemtable` plus the engine's read path. Reverting to A1 would mean making the skiplist
`@NotThreadSafe` and folding reads back under the write lock — mechanical, no format or API change.
Moving to B2 (arena) is the planned forward path, done behind the unchanged `Memtable` seam, and touches
no on-disk format. None of this is an on-disk or wire change, so no migration is involved.

## References

- LevelDB `db/skiplist.h` — single-writer, lock-free-reader skiplist (the reference we follow).
- Pugh, "Skip Lists: A Probabilistic Alternative to Balanced Trees," CACM 1990 — the origin; branching
  factor and expected O(log n) search.
- Pebble `internal/arenaskl` — the arena-backed, offset-addressed variant deferred as B2.
- *Database Internals* ch. 7 (LSM structure, memtable) and the skiplist discussion.
- `documentation/conventions/concurrency-and-resources.md` §1–§2 (threading contract, immutable
  snapshots over locks).
- [[0002-hand-write-core-mechanisms]], [[0006-storage-backend-spi]], [[0008-durability-and-clock]]
