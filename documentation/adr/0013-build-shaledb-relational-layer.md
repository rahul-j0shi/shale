# 0013. Build a relational layer, ShaleDB, above the engine in new modules

- **Status:** Accepted
- **Date:** 2026-09-26
- **Milestone:** D1 (decided now so the completion plan can schedule it; no code before M8 ships)
- **Reversible:** yes — the layer lives in its own modules and writes through the existing
  `StorageBackend` SPI; deleting it touches no engine format, API or module below it.

## Context

The charter (`roadmap/shale-roadmap.md` §A) lists as non-goals "SQL/query planner, secondary
indexes, a full type system" and "a production network server". ADR-0006 repeats it: the SPI is
byte keys and values, "no query layer — a non-goal". That scope made sense for a project whose
only question was *how does an LSM engine work*.

The owner's goal for the finished project is wider: show the engine doing the job it exists for —
being the **storage layer of a complete database** that a real CRUD application runs on. A
storage engine with no caller is a library with tests; a reviewer cannot see it work. Every
production LSM engine sits under exactly such a layer (MyRocks under MySQL, RocksDB under
CockroachDB and TiKV/TiDB, Cassandra's own CQL layer), and the mapping of tables and indexes onto
ordered keys is itself a core database mechanism worth building by hand.

The constraints that drive the choice: `shale-core` must stay an embeddable, JDK-only engine with
no dependency on anything above it (CLAUDE.md §2); the new layer's own mechanisms must obey the
prime directive (N1); and the engine milestones M5–M8 must not be displaced, because a relational
layer on an engine without compaction, atomic batches or snapshots would be built on sand.

## Options considered

### Option A — Keep the non-goal; finish the engine and Flotilla only
The charter as written. Smallest scope. But the engine's value stays invisible to anyone who does
not read the tests, and the owner's stated portfolio goal is not met.

### Option B — Put tables and SQL inside `shale-core`
One module, no new wiring. Violates the architectural point of the project: the engine would stop
being an embeddable byte-KV store, and the SPI that the B+Tree (M8b) and Raft (M9) layers rely on
would grow relational concerns.

### Option C — New modules above `shale-core`, everything hand-written
`shale-db` (order-preserving key encoding, row format, catalog, SQL parser, planner, Volcano
executor, optimistic transactions) depends only on `shale-core`'s public SPI. `shale-server` (a
single-node HTTP/JSON server and a shell) depends on `shale-db`. `shale-demo` (the CRUD
application) talks to `shale-server` over its protocol. All three carry zero runtime dependencies,
as `shale-core` does. This is the MyRocks/CockroachDB layering: SQL over an ordered KV interface.

### Option D — Reuse an existing query engine (Apache Calcite, H2) over Shale
Fastest route to SQL. But parsing, planning and execution would become someone else's code, which
is exactly what ADR-0002 rejects for anything the project sets out to learn. Rejected for the same
reason as a bloom-filter library.

## Decision

**Option C.** The charter's non-goals are amended:

- **Now in scope:** a SQL subset (DDL, single- and multi-table DML/queries, joins, aggregates,
  `EXPLAIN`), primary and secondary indexes, a catalog, serializable multi-statement transactions
  on one node, a single-node server with a documented protocol, and a CRUD demo application.
- **Still out of scope:** the full SQL standard, a cost-based optimizer, distributed SQL and
  distributed transactions (M11 stays a non-goal), authentication/TLS/multi-tenancy, a JDBC driver
  and the PostgreSQL wire protocol (both recorded as stretch only).

The module graph becomes:

```
shale-demo ──▶ shale-server ──▶ shale-db ──▶ shale-core ◀── flotilla-raft ◀── flotilla-server
shale-bench ──▶ shale-core (and shale-db from D-track benchmarks on)
```

`shale-core` still depends on nothing but the JDK. `shale-db` depends only on `shale-core`'s public
API, never on its `internal` packages and never on `flotilla-*`. N1 extends to the new modules:
the relational layer's mechanisms are hand-written, and their runtime dependency graph is empty
(the JDK's own `jdk.httpserver` and `java.net.http` modules are the JDK, not dependencies).

**Sequencing:** the ShaleDB track (D1–D6) starts after M8, because it needs M7's atomic
`WriteBatch` and `Snapshot` and benefits from compaction and filters. The COW B+Tree (M8b) and
Flotilla (M9–M10) move after D6 and become optional. See
[`roadmap/completion-plan.md`](../roadmap/completion-plan.md).

## Rationale

Option C is the only option that meets the owner's goal without weakening the one boundary the
project exists to demonstrate. It also strengthens the existing decisions instead of competing
with them: the SPI (ADR-0006) becomes the seam a real database runs through, and at M8b the same
SQL layer can run unchanged over the B+Tree backend — a sharper demonstration of the seam than a
benchmark alone.

Ordering the track after M8 is deliberate: the relational layer's correctness (a row and its index
entries commit atomically; a query reads one consistent state) rests on M7's batches and snapshots,
and its performance story rests on M6's compaction and M8's measurements.

## Consequences

**Positive:** a runnable, visible end-to-end system (application → SQL → engine → disk); a second
body of hand-written mechanisms (encoding, planning, execution, concurrency control) that interviews
ask about constantly; a real client for the engine, which will surface API gaps tests do not.

**Negative:** roughly 13–18 more focused weeks before the portfolio release; three more modules to
maintain; the B+Tree capstone and Flotilla are no longer on the critical path and may not be built.

**Neutral:** ADR-0003's four-module split is extended, not replaced — its Neutral consequence
already anticipated further modules attaching without disturbing the split. ADR-0006's SPI stays a
byte-KV interface; the query layer sits above it, as ADR-0006 required.

**If we need to reverse this:** delete the three modules, remove their `settings.gradle.kts`
entries, and restore the charter's non-goals. No engine format, API or data is affected.

## References

- Matsunobu et al., "MyRocks: LSM-Tree Database Storage Engine Serving Facebook's Social Graph"
  (VLDB 2020) — a SQL engine over an LSM engine via an order-preserving key encoding.
- Taft et al., "CockroachDB: The Resilient Geo-Distributed SQL Database" (SIGMOD 2020) — the
  table/index-to-KV mapping.
- Huang et al., "TiDB: A Raft-based HTAP Database" (VLDB 2020).
- Graefe, "Volcano — An Extensible and Parallel Query Evaluation System" (IEEE TKDE 1994).
- Petrov, *Database Internals*, ch. 1 (DBMS architecture: query processor over storage engine).
- CMU 15-445 (Pavlo) — query processing, optimization and concurrency control lectures.
