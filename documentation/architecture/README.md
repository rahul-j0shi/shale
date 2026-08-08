# Architecture — as-built detail

The top-level [README](../../README.md#architecture--the-complete-project-scope) draws the
**complete project scope** — the whole system, built and planned, in four overview diagrams
(modules, the engine, Flotilla, verification).

This folder holds the finer-grained **as-built** designs: the actual types and flows that
exist in the code, milestone by milestone. Unlike the roadmap (which describes intent) these
track what is really implemented, and they change as the code does.

## How to read one

Every page answers the same three questions, in order:

1. **In the whole project** (§1) — *why this milestone exists*: what it hands the rest of the
   engine and what it takes from the milestones before it. This is the glue between the README
   overview and the detail below.
2. **High-level design, HLD (§2)** — *what the engine does*: the milestone's flows, contracts,
   and interplay, drawn as system-level diagrams.
3. **Low-level design, LLD (§3)** — *how it works from first principles*: the classes, byte
   layouts, state machines, and concurrency reasoning behind those flows.
4. **What proves it (§4)** — the tests that pin each behaviour.

| Milestone | Doc | HLD | LLD |
|---|---|---|---|
| M0 | [m0-shale-core.md](m0-shale-core.md) | The `StorageBackend` SPI and the contracts the engine is built on | The 17 types and the differential model harness |
| M1 | [m1-wal-and-map.md](m1-wal-and-map.md) | Write path, durability modes, recovery, read path | WAL fragment layout, the recovery state machine, the types |
| M2 | [m2-memtable-and-handoff.md](m2-memtable-and-handoff.md) | Write path + memtable switch, cross-memtable reads, recovery | Skiplist structure, safe publication, the types |
| M3 | [m3-sstable-and-flush.md](m3-sstable-and-flush.md) | Flush, reads across tables, recovery-flush | SSTable block-table format, the block reader, the types |

Each milestone's release note lives under [`../roadmap/`](../roadmap/); the decisions behind
these shapes are in [`../adr/`](../adr/).
