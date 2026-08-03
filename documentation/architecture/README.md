# Architecture — as-built detail

The top-level [README](../../README.md#architecture--the-complete-project-scope) draws the
**complete project scope** — the whole system, built and planned, in four overview diagrams
(modules, the engine, Flotilla, verification).

This folder holds the finer-grained **as-built** diagrams: the actual types and flows that
exist in the code, module by module and milestone by milestone. Unlike the roadmap (which
describes intent) these track what is really implemented, and they change as the code does.

| Milestone | Doc | Covers |
|---|---|---|
| M0 | [m0-shale-core.md](m0-shale-core.md) | `shale-core` type graph (all 17 types) and the model harness |
| M1 | [m1-wal-and-map.md](m1-wal-and-map.md) | WAL write path, block-fragment layout, the recovery state machine, durability, and the read path |
| M2 | [m2-memtable-and-handoff.md](m2-memtable-and-handoff.md) | Skiplist structure, single-writer/lock-free-reader safe publication, the memtable switch and WAL roll, cross-memtable reads, and recovery |
| M3 | [m3-sstable-and-flush.md](m3-sstable-and-flush.md) | SSTable block-table format, the block reader, flush with WAL-segment reclamation (D3), reads across memtables and tables, and recovery-flush |

Each milestone's release note lives under [`../roadmap/`](../roadmap/); the decisions behind
these shapes are in [`../adr/`](../adr/).
