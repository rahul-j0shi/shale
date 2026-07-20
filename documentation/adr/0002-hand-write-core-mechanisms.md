# 0002. Hand-write all core storage mechanisms

- **Status:** Accepted
- **Date:** (fill in)
- **Milestone:** M0
- **Reversible:** no — reversing it removes the project's reason to exist

## Context

Every mechanism this project sets out to build has a mature, faster, better-tested JVM
implementation available as a dependency. Guava has a bloom filter. `ConcurrentSkipListMap`
is a production skiplist. Apache Ratis is a battle-tested Raft. RocksDB's JNI bindings
would provide the entire engine, and would beat anything written here by a wide margin
on every benchmark.

At every milestone there will therefore be a locally rational argument for taking the
library: it is faster, it is correct, it is free, and it lets us reach the interesting
part sooner. Applied repeatedly, that argument produces a thin orchestration layer over
other people's engines — a project that demonstrates dependency management.

The stated purpose (`documentation/roadmap/`) is to understand these mechanisms in depth
and hold complete control over the engine. That purpose is served by the act of
implementation, not by the resulting artifact.

## Options considered

### Option A — Use libraries wherever they exist, focus on integration
Fastest path to a running distributed store. Produces working software soonest. But the
concepts we set out to learn are exactly the ones inside the libraries, and the finished
system would demonstrate nothing that a weekend of gluing could not.

### Option B — Hand-write everything, including JDK-provided plumbing
Maximum purity. Also means writing our own hash map, our own thread pool, and our own
byte buffers — none of which are project subjects, all of which consume the time
budget that compaction and consensus need.

### Option C — Hand-write project subjects, use the JDK freely for plumbing
Draw an explicit line: anything in the roadmap's component inventory is written by hand;
general-purpose infrastructure that is not a subject of study is taken from the JDK.
Requires maintaining an allowlist and defending the line under time pressure.

## Decision

Option C. Every component named in the roadmap's inventory — WAL, memtable/skiplist,
SSTable encoding, compaction, bloom filter, block cache, MVCC, manifest, iterators,
Raft, sharding, distributed transactions — is implemented from first principles in this
repository, with no third-party implementation of that concept present even transitively.

`shale-core` carries zero runtime dependencies. The allowlist and the ban list live in
`documentation/conventions/java-style.md` §1 and are enforced in CI.

General-purpose JDK facilities that are not project subjects (`ArrayDeque`,
`ReentrantLock`, `CompletableFuture`, `MemorySegment`) are used freely.

## Rationale

The line is drawn at "is this a thing the roadmap says we are here to learn?" rather
than at any technical criterion, because the constraint is pedagogical rather than
architectural. Making that explicit is more honest than inventing a performance or
licensing justification, and it makes the line easy to apply to cases not yet imagined.

Zero runtime dependencies in `shale-core` is chosen over a curated short list because a
bright line is enforceable and a judgement call is not. "No dependencies" survives
contact with a tired evening; "only well-chosen dependencies" does not.

A secondary benefit: a dependency-free engine module is genuinely embeddable, and the
constraint forces the module boundary between engine and cluster to stay real.

## Consequences

**Positive:** every mechanism is understood and modifiable. The engine can be
instrumented anywhere, which is what makes the amplification measurements in the
roadmap possible at all. No library's design decisions are silently inherited.
`shale-core` stays embeddable and trivially auditable.

**Negative:** slower progress, and a hand-written skiplist and bloom filter will be
slower and buggier than Guava's for a long time. Some wheels will be reinvented badly
before they are reinvented adequately. The crash and property test suites
(`testing.md`) are not optional under this constraint — they are the compensating
control for writing our own primitives.

**Neutral:** benchmark numbers will not be competitive with RocksDB and are not intended
to be. The comparison that matters is Shale-LSM against Shale-B+Tree on the same
harness (roadmap M8), where both sides are ours.

**If we need to reverse this:** we would not reverse it; we would end the project and
use RocksDB. That is the honest statement of what this decision is.

## References

- `documentation/roadmap/` — component inventory and project charter
- Petrov, *Database Internals* — the structure this project follows
- `documentation/conventions/java-style.md` §1 — the enforced allowlist
