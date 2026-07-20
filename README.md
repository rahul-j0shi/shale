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

**Status:** early scaffolding. Charter, conventions, and the M0–M11 roadmap are in
place; engine implementation begins at M0. This repository is built strictly
bottom-up — durability and crash-recovery correctness come before any optimisation.

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
