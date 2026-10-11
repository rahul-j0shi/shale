# Architecture — as-built detail

[project-scope.md](project-scope.md) draws the **complete project scope** — the whole system,
built and planned, in four overview diagrams (modules, the engine, the database layer, verification).
The top-level [README](../../README.md) keeps only the status table and the module graph.

This folder holds the finer-grained **as-built** designs: the actual types and flows that
exist in the code, milestone by milestone. Unlike the roadmap (which describes intent) these
track what is really implemented, and they change as the code does.

## How to read one

Every page has the same four parts, in order:

1. **In the whole project** (§1) — *why this milestone exists*: what it hands the rest of the
   engine and what it takes from the milestones before it. This is the glue between the README
   overview and the detail below.
2. **High-level design, HLD (§2)** — *what the engine does*: the milestone's flows, contracts,
   and interplay, drawn as system-level diagrams.
3. **Low-level design, LLD (§3)** — *how it works from first principles*: the classes, byte
   layouts, state machines, and concurrency reasoning behind those flows.
4. **What proves it (§4)** — the tests that pin each behaviour.

| Page | | |
|---|---|---|
| [project-scope.md](project-scope.md) | The complete component scope, built and planned — modules, engine, database layer, verification | |

| Milestone | Doc | HLD | LLD |
|---|---|---|---|
| M0 | [m0-shale-core.md](m0-shale-core.md) | The `StorageBackend` SPI and the contracts the engine is built on | The 17 types and the differential model harness |
| M1 | [m1-wal-and-map.md](m1-wal-and-map.md) | Write path, durability modes, recovery, read path | WAL fragment layout, the recovery state machine, the types |
| M2 | [m2-memtable-and-handoff.md](m2-memtable-and-handoff.md) | Write path + memtable switch, cross-memtable reads, recovery | Skiplist structure, safe publication, the types |
| M3 | [m3-sstable-and-flush.md](m3-sstable-and-flush.md) | Flush, reads across tables, recovery-flush | SSTable block-table format, the block reader, the types |
| M4 | [m4-merge-iterator.md](m4-merge-iterator.md) | The two read paths, a scan end to end, the reconciliation rules | The heap, the two-level SSTable iterator, cursor ownership |
| M4.5 | [m4-5-integrity-boundaries.md](m4-5-integrity-boundaries.md) | The recovery case study: two one-bit header faults, the invariant, why the obvious patches failed, the decision | Log framing v2, the tail decision table, the decoder contract |

**Planned explainers.** Each milestone's docs step writes one, from what was actually built:

| Milestone | Explainer (written in) | It will show |
|---|---|---|
| M5 | `m5-manifest-and-recovery.md` ([plan](../roadmap/m5-manifest-and-recovery.md) Step 9) | the open and flush sequences with every sync marked; `Version` ownership |
| M5.5 | `m5-5-concurrent-write-path.md` ([plan](../roadmap/m5-5-concurrent-write-path.md) Step 6) | submit → group → sync → apply; stalls and watermarks |
| M6 | `m6-compaction.md` ([plan](../roadmap/m6-compaction.md) Step 8) | levels, one compaction end to end, the lookup-order invariant, range selection |
| M7 | `m7-batches-snapshots-bloom.md` ([plan](../roadmap/m7-batches-snapshots-bloom.md)) | batches, visibility at a sequence, the filter, per-level statistics |
| M8 | `m8-checkpoint-and-benchmarks.md` ([plan](../roadmap/m8-benchmark-suite.md) Step 8) | the online checkpoint; the two harnesses |
| D1 | `d1-record-layer.md` ([plan](../roadmap/d1-record-layer.md) Step 8) | tables and indexes as ordered keys; one row's bytes end to end |
| D2 | `d2-sql-and-execution.md` ([plan](../roadmap/d2-sql-and-execution.md)) | SQL text → AST → bound plan → operators |
| D3 | `d3-joins-aggregates.md` ([plan](../roadmap/d3-joins-aggregates-ordering.md)) | joins, aggregates, ordered pages |
| D4 | `d4-transactions.md` ([plan](../roadmap/d4-transactions.md) Step 7) | the oracle, validation, commit order |
| D5 | `d5-postgres-wire-protocol.md` ([plan](../roadmap/d5-postgres-wire-protocol.md)) | a session's messages, simple and extended |
| D6 | `d6-cost-accounting.md` ([plan](../roadmap/d6-cost-accounting.md) Step 6) | one `INSERT` traced from SQL to fsync, every counter at its `file:line` |
| D7 | `d7-demo-and-trace-view.md` ([plan](../roadmap/d7-demo-and-launch.md) Step 6) | Shelf's request path; how one trace becomes the five drawn parts |

Each page describes the code **at its milestone's tag**. When a later milestone renames or
replaces something, the old page gets a one-line note pointing forward, rather than being
rewritten, so it still matches the code a reader checks out at that tag. Every finished milestone
from M4.5 on adds a page here, in its docs step.

What each milestone shipped is in the [changelog](../../CHANGELOG.md); the decisions behind
these shapes are in [`../adr/`](../adr/).
