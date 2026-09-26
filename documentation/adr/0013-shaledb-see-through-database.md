# 0013. ShaleDB: a see-through database — purpose, scope, modules and dependencies

- **Status:** Accepted
- **Date:** 2026-09-26
- **Milestone:** applies from M5; first new module at D1
- **Reversible:** yes — the scope and module layout can change without touching any engine format
  or API; the decisions each milestone makes under it carry their own ADRs.

This record replaces the original module decision (the former ADR-0003) and settles what the
project is for. Every later scope question is answered against the purpose stated here.

## Context

Until now the project was "an LSM engine, then a Raft-replicated, range-sharded store on it". The
stated reason was learning. Learning explains why the author builds it; it does not explain why
the thing should exist, which is the first question a reviewer or interviewer asks.

There is a real gap to point at: **a statement's full storage cost is hard to see, and hardest
on LSM storage**, whose costs are deferred and indirect (write amplification, tombstones that slow
later scans, compaction debt). Production systems expose fragments:
- PostgreSQL's `EXPLAIN (ANALYZE, BUFFERS, WAL)` gives buffers and WAL bytes over heap and B-tree
  storage;
- CockroachDB's `EXPLAIN ANALYZE` gives MVCC step and seek counts per scan;
- RocksDB's `perf_context` gives per-operation engine counters, which MyRocks aggregates per table.

None follows one statement from its plan, through index maintenance and the WAL, to the
compaction work it creates. Each fragment also lives inside a codebase too large to read. The
question "what does this `INSERT` with two secondary indexes actually cost?" has no single place
to be answered.

**You can only attribute cost across layers you own.** A system assembled from RocksDB and a
borrowed SQL engine could not trace a statement's cost end to end without instrumenting both
from the outside. Owning every layer is the precondition for the product, not a purity rule.

## Options considered

### A — The engine alone (the original charter's first half)
A measured LSM engine behind a byte-KV API. Solid, but its costs stay abstract: no application
ever meets them, and the project has nothing a reviewer can run.

### B — Engine plus a distributed layer (the original charter)
Raft replication and range sharding on the engine. The cost of distribution is a different class
(network, consensus), does not serve the question above, and is 12–20 more weeks. Raft is also
among the most common portfolio projects, so it differentiates least.

### C — Engine plus a SQL layer built on an existing query engine (Calcite, H2)
Fastest route to SQL, but the query layer becomes someone else's code. Cost attribution would stop
at its boundary, and ADR-0002 rules out borrowing the mechanisms under study.

### D — A see-through relational database, every layer hand-written
The LSM engine, a relational layer on its public SPI, the PostgreSQL wire protocol so standard
tools connect, and per-statement cost accounting from SQL to fsync.

## Decision

**Option D.** The project is **ShaleDB — a relational database built from scratch in Java,
designed so the cost of every statement is visible from the SQL down to the disk.**

**What it claims, and how each claim is proven:**

| Claim | Proven by |
|---|---|
| **Visible** — `EXPLAIN ANALYZE` reports each statement's plan and its storage cost | Memtable and SSTable probes, bloom-filter skips, blocks read, versions and tombstones skipped, WAL bytes, fsyncs shared through group commit, estimated compaction debt |
| **Measured** — storage design choices are traced through to SQL workloads | Benchmarks, with RocksDB and SQLite as reference points |
| **Durable** | Crash tests at every byte and file operation; a `kill -9` test against the running server |
| **Usable** | `psql` and a standard PostgreSQL driver connect; a CRUD application runs on it |

**Scope.**
- **In:**
  - the LSM engine (manifest, concurrent write path, leveled and size-tiered compaction, atomic
    batches, snapshots, bloom filters);
  - a benchmark suite with reference baselines;
  - an order-preserving record layer with primary and secondary indexes;
  - a SQL subset with a rule-based planner and a Volcano executor, including joins and aggregates;
  - serializable transactions;
  - a PostgreSQL wire-protocol server;
  - per-statement cost accounting;
  - a CRUD demo application.
- **Out:**
  - replication, sharding and distributed transactions (the costs explained here are single-node);
  - a second storage backend;
  - a block or table cache (the OS page cache serves the demo's scale; the thesis does not need it);
  - the full SQL standard and a cost-based optimizer;
  - authentication, TLS and multi-tenancy;
  - production use and competitive performance.

**Modules** — one repository, one module per layer, dependency direction enforced by each build
script:

```
shale-demo ──▶ (PostgreSQL wire protocol) ──▶ shale-server ──▶ shale-db ──▶ shale-core
shale-bench ──▶ shale-core, shale-db
```

| Module | Holds | Created at |
|---|---|---|
| `shale-core` | the LSM engine behind the `StorageBackend` SPI | exists |
| `shale-bench` | JMH microbenchmarks and workload harnesses | exists |
| `shale-db` | record layer, catalog, SQL, planner, executor, transactions, cost accounting | D1 |
| `shale-server` | the PostgreSQL wire-protocol server | D5 |
| `shale-demo` | the CRUD application, a client of the server | D7 |

`shale-core` depends on nothing but the JDK and never on any module above it. `shale-db` uses
only `shale-core`'s public API, never its `internal` packages. `shale-demo` reaches the database
only over the wire protocol, as any application would.

**Dependency policy** (supersedes nothing in N1; it states where N1's line falls per module):
- `shale-core`, `shale-db`, `shale-server`: **zero runtime dependencies**, each checked by
  `verifyNoRuntimeDependencies`. Their mechanisms, including the wire protocol, are hand-written.
- `shale-demo`: it is an *ordinary application*, so it uses what one would. That means the
  PostgreSQL JDBC driver (proving a standard driver works is the point), and the JDK's built-in
  HTTP server for its web UI, with a Checkstyle exception for `com.sun.net.httpserver` scoped
  to this module.
- `shale-bench`: RocksDB (JNI) and SQLite (JDBC) as **reference baselines only**, so every
  number has context. A Checkstyle exception for `org.rocksdb` is scoped to this module. No
  production module may depend on `shale-bench`.

## Rationale

The purpose converts the project from "I built a database to learn" into "I built a database that
answers a question production systems leave open — and learned every layer by doing it". Each
scope decision follows from that purpose. An LSM engine, because its costs are the least intuitive
and most worth making visible. SQL on top, because developers only ever meet storage costs through
the statements they write. The PostgreSQL protocol, so the visibility is reachable with tools
people already use. Single-node, because distribution adds costs of a different kind. No second
backend and no block cache, because neither changes what can be seen.

The dependency policy draws N1's line where the thesis needs it. The layers whose cost is being
explained are hand-written; the application and the benchmark references are deliberately
ordinary, because their job is to be the outside world.

## Consequences

**Positive:** one coherent pitch and interview answer; a runnable system; every milestone has a
test for whether it belongs ("does it make cost visible, measured or proven?"); a much smaller
scope than the original charter.

**Negative:** no replication or distributed-systems content; the cost-accounting milestone adds
per-operation statistics to the engine's read and write paths, which the M7 API must be designed
for; three new modules to maintain.

**Neutral:** the `StorageBackend` SPI (ADR-0006) stays a byte-KV interface — ShaleDB sits above it.

**If we need to reverse this:** narrowing back to the engine alone means deleting the three
ShaleDB modules; no engine format or API changes. Widening to distribution would be a separate
project on the engine's SPI, `WriteBatch` and `Snapshot`.

## References

- Matsunobu et al., "MyRocks: LSM-Tree Database Storage Engine Serving Facebook's Social Graph"
  (VLDB 2020) — SQL over an LSM engine, and the cost of secondary indexes on one.
- Taft et al., "CockroachDB: The Resilient Geo-Distributed SQL Database" (SIGMOD 2020) — the
  table-and-index-to-KV mapping.
- RocksDB wiki, "PerfContext and IOStatsContext" — per-operation storage counters.
- PostgreSQL documentation, "Using EXPLAIN" and "Frontend/Backend Protocol".
- Athanassoulis et al., "Designing Access Methods: The RUM Conjecture" (EDBT 2016).
- Graefe, "Volcano — An Extensible and Parallel Query Evaluation System" (IEEE TKDE 1994).
- Petrov, *Database Internals*, ch. 1 (query processor over storage engine) and ch. 7.
