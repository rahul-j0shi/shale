# Shale &nbsp;·&nbsp; Flotilla

A hand-written **LSM-tree storage engine** (`Shale`) and the **Raft-replicated,
range-sharded distributed store** built on top of it (`Flotilla`) — implemented from
first principles in Java, with no third-party library for any core mechanism.

> **The prime directive: the implementation *is* the product.**
> This is a study-and-portfolio project. Its value is that write-ahead logging,
> skiplists, SSTable encoding, compaction, bloom filters, MVCC, and consensus are each
> built by hand and understood in depth — not assembled from libraries. A dependency
> that implements a core concept is disallowed by rule, even when it would be faster
> and more correct. See [`CLAUDE.md`](CLAUDE.md) §4.

**Status:** **M0 complete** (tag `m0-skeleton`). The storage SPI, comparator, internal-key
encoding, exception hierarchy, and the seeded `TreeMap` model harness are in the code;
`./gradlew build` is green on JDK 25 (29 tests). Everything past M0 in the roadmap is not
yet written. Built strictly bottom-up — durability and crash-recovery correctness come
before any optimisation.

---

## Why this project

An LSM engine is unusually dense with deep-systems concepts that rarely appear in
typical application code: append-only durability, crash recovery and replay, immutable
file lifecycle with reference counting, background compaction with write-stall
backpressure, probabilistic membership (bloom filters), MVCC via sequence numbers, and
heap-based multi-way merge iteration — and then, on top, leader election, log
replication, snapshotting, range sharding, and distributed transactions.

The intellectual spine is the **RUM conjecture** (Athanassoulis et al., EDBT 2016): an
access method can bound at most two of *read*, *update*, and *memory* overhead, forcing
the third. Owning the engine means owning those knobs — compaction policy, bloom
bits-per-key, block size, memtable size — and being able to *measure* the tradeoff
rather than assert it.

## Architecture

```
flotilla-server ──▶ flotilla-raft ──▶ shale-core
       └───────────────────────────────────┘
```

`shale-core` is an embeddable single-node engine that **depends on nothing but the
JDK**. It must never depend on any networking, RPC, or clustering code — that boundary
is the architectural point of the project.

| Module | Role |
|---|---|
| `shale-core` | The LSM engine. WAL, memtable, SSTable, compaction, filters, MVCC, manifest. |
| `shale-bench` | JMH microbenchmarks + YCSB / db_bench-style macro harnesses. |
| `flotilla-raft` | Consensus from scratch: election, log replication, snapshotting. |
| `flotilla-server` | RPC, range sharding, split/merge, routing, placement/metadata. |

## Architecture in depth — the code as it exists (M0)

Strictly what is in the repository at tag `m0-skeleton` — nothing planned is drawn. Three
views: the modules and their build gate, the `shale-core` type graph, and the one runnable
flow (the model harness). The WAL, memtable, SSTable, compaction, Raft, and sharding layers
in the [Roadmap](#roadmap) below **do not exist yet** — `shale-bench`, `flotilla-raft`, and
`flotilla-server` are empty build shells.

### 1 · Modules, dependency direction, and the build gate

```mermaid
flowchart TB
  subgraph repo["shale repo · Gradle 9.6.1 · vendored JDK 25 in .tools/"]
    core["shale-core<br/>17 main types · 11 test classes · 5 harness helpers<br/>JDK-only (zero runtime deps, N1)"]
    bench["shale-bench<br/>build only — JMH wired, no source yet"]
    raft["flotilla-raft<br/>build only — no source yet"]
    server["flotilla-server<br/>build only — no source yet"]
  end
  server -->|implementation| raft
  server -->|implementation| core
  raft -->|api| core
  bench -->|jmh| core
  gate["Build gate (build.gradle.kts)<br/>spotless google-java-format · checkstyle · javac -Xlint:all -Werror<br/>tasks: test · crashTest · soakTest · catalog: junit · assertj · jqwik · jmh"]
  gate -. enforces .-> core
```

`shale-core` depends on nothing but the JDK; the other three depend inward only — the arrows
are enforced in each module's `build.gradle.kts`, not by convention.

### 2 · `shale-core` type graph (all 17 types, real relationships)

```mermaid
flowchart TB
  subgraph api["dev.shale — public API"]
    sb["StorageBackend — interface<br/>put · delete · get · scan · comparator · close"]
    cur["Cursor — interface · @NotThreadSafe<br/>isValid · next · key · value · close"]
    dur["Durability — enum · @Immutable<br/>NONE · SYNC · GROUP"]
    br["ByteRange — record · @Immutable<br/>(array, offset, length) · of()"]
    kc["KeyComparator — interface<br/>compare(ByteRange,ByteRange) · name · compare(byte[],byte[])"]
    bwc["BytewiseComparator — final class, singleton · @ThreadSafe<br/>INSTANCE · unsigned lexicographic"]
    se["ShaleException — abstract class"]
    ce["CorruptionException<br/>offsetBytes · expectedValue · actualValue"]
    stx["StorageException"]
    ese["EngineStateException"]
  end

  subgraph ann["dev.shale.internal.annotations"]
    marks["@ThreadSafe · @NotThreadSafe · @Immutable"]
  end
  subgraph cod["dev.shale.internal.coding"]
    le["LittleEndian — final class · @ThreadSafe<br/>putFixed64 · getFixed64"]
  end
  subgraph key["dev.shale.internal.key"]
    vt["ValueType — enum · @Immutable<br/>DELETE=0x00 · PUT=0x01 · FOR_SEEK · fromCode"]
    ikey["InternalKey — record · @Immutable<br/>userKey · sequenceNumber(56b) · valueType<br/>MAX_SEQUENCE · packTrailer · encode · decode"]
    ikc["InternalKeyComparator — final class · @ThreadSafe<br/>userKey asc, trailer desc"]
  end

  ac["AutoCloseable (JDK)"]
  rt["RuntimeException (JDK)"]

  sb -->|extends| ac
  cur -->|extends| ac
  bwc -->|implements| kc
  ikc -->|implements| kc
  ce -->|extends| se
  stx -->|extends| se
  ese -->|extends| se
  se -->|extends| rt

  sb -.uses.-> dur
  sb -.uses.-> cur
  sb -.uses.-> kc
  kc -.uses.-> br
  ikey -.uses.-> le
  ikey -.uses.-> vt
  ikey -.uses.-> ce
  ikc -.uses.-> br
  ikc -.uses.-> le
  vt -.uses.-> ce
  ann -. "one marker per type" .-> api
  ann -.-> cod
  ann -.-> key
```

Per-type coverage (29 cases): `ByteRangeTest`, `BytewiseComparatorTest` + `…PropertyTest`,
`CorruptionExceptionTest`, `LittleEndianPropertyTest`, `ValueTypeTest`, `InternalKeyTest` +
`…PropertyTest`, `InternalKeyComparatorTest`.

### 3 · The one executable flow — the model harness (`dev.shale.model`, test scope)

```mermaid
flowchart TB
  seeds["Seeds.resolve()<br/>-Dshale.test.seed or SecureRandom"]
  smt["StorageBackendModelTest · @Tag(model)<br/>5000 seeded ops: 70% put / 30% delete"]
  rnd["java.util.Random(seed)"]
  backs["Backends<br/>drain(Cursor) · assertMatches (AssertJ)"]
  refb["ReferenceBackend implements StorageBackend<br/>TreeMap keyed by InternalKey.encode()<br/>ordered by InternalKeyComparator"]
  refm["ReferenceModel — oracle<br/>TreeMap ordered by BytewiseComparator"]
  ikstack["InternalKey · InternalKeyComparator · ValueType<br/>(encode/decode · ceilingEntry lookup · tombstones)"]
  hst["HarnessSelfTest"]
  buggy["BuggyBackend.ignoringDeletes()"]

  seeds --> smt
  smt --> rnd
  smt -->|"put/delete (Durability.NONE)"| refb
  smt -->|"same ops"| refm
  smt -->|"after every 100 ops + final"| backs
  backs -->|"get(probeKeys) + full scan"| refb
  backs -->|compare| refm
  refb -.uses.-> ikstack
  hst -->|wraps| buggy
  buggy -->|delegates puts, drops deletes| refb
  hst -->|"assertMatches must throw"| backs
```

`ReferenceBackend` is the only thing that actually exercises the M0 encoding: it stores
encoded `InternalKey`s in a `TreeMap` ordered by `InternalKeyComparator`, resolves a `get`
via a `FOR_SEEK`/`MAX_SEQUENCE` ceiling lookup, and hides tombstones on `scan`. The oracle
is a plain `TreeMap`; the harness asserts they never diverge, and `HarnessSelfTest` proves
the assertion bites by running a backend that ignores deletes.

## Roadmap

Strictly ordered; each milestone ends in a runnable, tested artifact.

| | Milestone | Yields |
|---|---|---|
| **M0** | Skeleton & interfaces | `get/put/delete`, `StorageBackend`, comparator, internal-key encoding, `TreeMap` reference-model harness |
| **M1** | WAL + in-memory map | Append-only log (CRC + segments), recovery by replay, `sync` toggle — durability |
| **M2** | MemTable + immutable handoff | Hand-written skiplist, memory accounting, memtable switching |
| **M3** | SSTable write + flush | Data blocks, restart points, block index, footer |
| **M4** | Multi-SSTable reads | Heap-based multi-way merge, reconciliation, tombstones |
| **M5** | Manifest + recovery hardening | Version edits, atomic install, CURRENT, ref-counted lifecycle, crash tests |
| **M6** | Compaction | Size-tiered then leveled; scoring, file picking, background threads, write stalls |
| **M7** | Filters, cache, MVCC | Per-SSTable bloom, block/table cache, sequence-number snapshots, atomic batches |
| **M8** | COW B+Tree capstone | Copy-on-write B+Tree backend + full benchmark suite — the RUM tradeoff, measured |
| **M9** | Single Raft group | Engine as replicated state machine (snapshot = engine snapshot) |
| **M10** | Multi-Raft sharding | Range partitions, split/merge/rebalance, PD-like metadata + TSO, routing |
| **M11** | Distributed transactions | Percolator 2PC with primary-key coordinator and TSO timestamps |

Full charter, component inventory, citations, and effort estimates:
[`documentation/roadmap/shale-roadmap.md`](documentation/roadmap/shale-roadmap.md).

## Engineering non-negotiables

Enforced by `CLAUDE.md` §4 and the [conventions](documentation/conventions/):

- **N1** No third-party implementation of a core concept (allowlist gated by ADR).
- **N2** Never silently change an on-disk format — version bump, golden-file round-trip, `Format-Change:` trailer.
- **N3** Every write path states where durability happens (`// DURABILITY:`).
- **N4** Corruption is never repaired silently — throw with file, offset, expected/actual.
- **N5** Every mutable field declares its concurrency contract.
- **N9/N10** Every core type cites its source; vocabulary matches the LSM literature exactly.

## Building

Target JDK **25 (LTS)**; off-heap work uses the Foreign Function & Memory API
(`Arena`, `MemorySegment`).

```bash
./gradlew build     # compile + checkstyle + unit tests
./gradlew test      # unit tests only
./gradlew crashTest # fault-injection suite
./gradlew :shale-bench:jmh
```

## References

Alex Petrov, *Database Internals* (the project's spine); the LSM-Tree paper
(O'Neil et al., 1996); Raft (Ongaro & Ousterhout, 2014); Monkey (Dayan et al., 2017);
Percolator (Peng & Dabek, 2010); and skyzh's *mini-lsm*. Per-component citations live
alongside each type — see the roadmap's resource list.
