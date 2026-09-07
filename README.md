# Shale &nbsp;·&nbsp; Flotilla

[![build](https://github.com/rahul-j0shi/shale/actions/workflows/build.yml/badge.svg)](https://github.com/rahul-j0shi/shale/actions/workflows/build.yml)

A hand-written **LSM-tree storage engine** (`Shale`) in Java, and the **Raft-replicated,
range-sharded distributed store** planned on top of it (`Flotilla`) — every core mechanism
implemented from first principles, with no third-party library for any of them.

> **The prime directive: the implementation *is* the product.**
> This is a study-and-portfolio project. Its value is that write-ahead logging, skiplists,
> SSTable encoding, compaction, bloom filters, MVCC and consensus are each built by hand and
> understood in depth. A dependency that implements a core concept is disallowed by rule —
> and the build enforces it: `shale-core` resolves **zero** runtime dependencies, checked on
> every push. See [`CLAUDE.md`](CLAUDE.md) §4.

## What exists today

**Built through M4.** The engine is durable, crash-consistent, and reads through a streaming
merge. It has no compaction yet, so tables accumulate — it is an LSM *write and read path*,
not yet a full LSM engine.

| Component | State |
|---|---|
| WAL — LevelDB block log, CRC32C, torn-tail policy, replay recovery | ✅ M1 |
| Memtable — hand-written skiplist, lock-free readers, immutable handoff | ✅ M2 |
| SSTable — prefix-compressed blocks, restart points, index, versioned footer | ✅ M3 |
| Flush — fsync + atomic rename *before* the WAL segment is dropped | ✅ M3 (synchronous) |
| Reads — heap-based multi-way merge, reconciliation, tombstones, pinned tables | ✅ M4 |
| Manifest, `CURRENT`, ref-counted file lifecycle | ❌ M5 |
| Group commit, background flush, write stalls | ❌ M5.5 |
| Compaction, bloom filters, block cache, MVCC snapshots | ❌ M6–M7 |
| Benchmarks (`shale-bench` — JMH plugin wired, no benchmarks written) | ❌ M8 |
| `flotilla-raft`, `flotilla-server` (empty build shells) | ❌ M9–M10 |

**How it is verified.** A crash test truncates the WAL at every byte offset and asserts
recovery yields a clean prefix. A bit-flip-at-every-offset test asserts every SSTable
corruption is detected. A frozen golden file guards the on-disk format against drift. A model
harness runs thousands of random operations against a `TreeMap` oracle, restarting the engine
mid-sequence. `./gradlew build` and `crashTest` are green on JDK 25.

## Why this project

An LSM engine is dense with mechanisms that rarely appear in application code: append-only
durability, crash recovery, immutable file lifecycles with reference counting, background
compaction with backpressure, probabilistic membership, MVCC, and multi-way merge iteration.

Its intellectual spine is the **RUM conjecture** (Athanassoulis et al., EDBT 2016): an access
method can bound at most two of *read*, *update* and *memory* overhead. Owning the engine
means owning those knobs — and being able to **measure** the tradeoff rather than assert it.

## Architecture

```
flotilla-server ──▶ flotilla-raft ──▶ shale-core
       └───────────────────────────────────┘
```

`shale-core` is an embeddable single-node engine that **depends on nothing but the JDK**, and
must never depend on networking, RPC or clustering code — that boundary is the architectural
point of the project. Full scope diagrams:
[`documentation/architecture/project-scope.md`](documentation/architecture/project-scope.md).
As-built designs, milestone by milestone:
[`documentation/architecture/`](documentation/architecture/).

## Roadmap

Strictly ordered; each milestone ends in a runnable, tested artifact.

| | Milestone | Yields |
|---|---|---|
| **M0–M4** | *Complete* | SPI · internal-key encoding · WAL · skiplist · SSTable + flush · merge iterator |
| **M5** | Manifest + recovery hardening | Version edits, atomic install, `CURRENT`, ref-counted lifecycle |
| **M5.5** | Concurrent write path | fsync outside the write lock, group commit, background flush + write stalls |
| **M6** | Compaction | Size-tiered then leveled; scoring, picking, write stalls, amplification counters |
| **M7** | Filters, cache, MVCC | Per-SSTable bloom, block/table cache, snapshots, atomic batches |
| **M8** | COW B+Tree capstone | Second backend + benchmark suite — the RUM tradeoff, measured (time-boxed) |
| **M9** | Single Raft group | Engine as replicated state machine; snapshot = engine snapshot |
| **M10** | Multi-Raft sharding | Range partitions, split/merge/rebalance, routing, placement metadata |

Charter, component inventory and citations:
[`documentation/roadmap/shale-roadmap.md`](documentation/roadmap/shale-roadmap.md). Percolator
transactions (M11) are a recorded **non-goal**. A dated assessment of the project — verified
findings and the plan to completion — is in
[`documentation/assessments/`](documentation/assessments/).

## Building

Target JDK **25 (LTS)**; off-heap work uses the Foreign Function & Memory API.

```bash
./gradlew build      # format, lint, compile, N1 dependency check, fast tests
./gradlew crashTest  # crash-recovery suite
```

## References

Alex Petrov, *Database Internals* (the spine); the LSM-Tree paper (O'Neil et al., 1996); Raft
(Ongaro & Ousterhout, 2014); Monkey (Dayan et al., 2017); Percolator (Peng & Dabek, 2010); and
skyzh's *mini-lsm*. Per-component citations live alongside each type.

Everything written about the project is mapped in
[`documentation/README.md`](documentation/README.md).
