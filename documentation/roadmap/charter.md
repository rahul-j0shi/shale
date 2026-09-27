# Charter — ShaleDB, a see-through database

The purpose, the design choices that follow from it, what is in and out of scope, and how
success is judged. The decision behind it is [ADR-0013](../adr/0013-shaledb-see-through-database.md);
the working order of milestones is the [completion plan](completion-plan.md).

## 1. Why this exists

**A database's real costs are hard to see, and hardest on LSM storage.** LSM engines now sit
under most write-heavy databases (RocksDB under MyRocks, CockroachDB and TiKV; Cassandra;
ScyllaDB), and their costs are deferred and indirect:
- write amplification is paid later, by compaction;
- a delete leaves a tombstone that slows later scans;
- a secondary index turns one `INSERT` into several writes, plus a read to check uniqueness.

These are exactly the costs people misjudge.

Production systems expose **fragments** of this:
- PostgreSQL's `EXPLAIN (ANALYZE, BUFFERS, WAL)` reports buffers and WAL bytes per statement, but
  over heap and B-tree storage;
- CockroachDB's `EXPLAIN ANALYZE` shows MVCC step and seek counts per scan;
- RocksDB's `perf_context` counts filter checks and blocks read per operation, and MyRocks
  aggregates them per table.

Each fragment lives inside a codebase of millions of lines. None follows a statement's cost the
whole way — from the plan, through index maintenance and the write-ahead log, to the compaction
work it leaves behind.

**ShaleDB is a relational database whose every statement can be traced from the SQL down to the
disk, in a codebase small enough to read end to end** — every counter maps to the code that
produces it. `psql` connects to it; `EXPLAIN ANALYZE` reports the plan *and* the storage cost —
memtable and table probes, bloom-filter skips, blocks read, dead versions skipped, WAL bytes, the
fsync it shared, and its estimated compaction debt. Its benchmarks connect storage design choices
to the latency of SQL statements.

**Why build every layer by hand.** Cost can only be attributed across layers you own. A system
assembled from RocksDB and a borrowed SQL engine could not trace a statement through both without
instrumenting them from the outside. Owning every layer — and understanding each one well enough to
explain it — is the precondition for the product. The learning follows from that; it is not the
excuse for it.

## 2. What it claims, and how each claim is proven

| Claim | Evidence |
|---|---|
| **Visible:** every statement's storage cost can be seen | `EXPLAIN ANALYZE` output, checked by tests against hand-computed costs |
| **Measured:** storage choices are traced to SQL latency | Benchmarks: leveled vs size-tiered compaction, bloom bits per key, memtable size, group commit — each also run through the SQL workload, with RocksDB and SQLite as reference points |
| **Durable:** no acknowledged write is ever lost | Crash tests at every byte and every file operation; a `kill -9` test against the running server; a model test against an in-memory oracle |
| **Correct:** transactions are serializable | Write-skew, lost-update and phantom tests; a serial-replay check over every tested schedule |
| **Usable:** it behaves like a database | `psql` and the standard PostgreSQL JDBC driver connect; a CRUD application runs on it |

## 3. Design choices that follow

| Choice | Because |
|---|---|
| **LSM storage** | Its costs are the least intuitive, so they are the most worth making visible |
| **A relational layer on the storage SPI** | Developers meet storage costs through the statements they write. The layering mirrors MyRocks and CockroachDB: tables and indexes become ordered keys |
| **PostgreSQL wire protocol** | The visibility is reachable with tools people already use; no custom client or shell |
| **Single node** | The costs being explained are single-node costs. Distribution adds network and consensus costs, a different subject |
| **Hand-written, zero-dependency core** | Attribution needs every layer instrumented. The build enforces the rule, so the claim is checkable (ADR-0002) |
| **Java 25** | Most hobby storage engines are written in Go or Rust; a JVM engine shows GC-aware design and the FFM API. The ecosystem also has the standard PostgreSQL driver the demo proves against |

## 4. Goals

1. **Correctness first:** never lose or corrupt acknowledged data; property, model, crash and
   serializability tests are gates, not extras.
2. **Visibility:** per-statement cost accounting through every layer, with the counters a reader
   needs to reason about read, write and space amplification.
3. **Measurement:** every performance statement in the README is a number with its method, not
   an adjective.
4. **Explainability:** the code reads like a book — each mechanism cites the literature it
   follows (N9), and each expensive decision has an ADR with its rejected alternatives.

## 5. Non-goals

- Replication, sharding or distributed transactions.
- A second storage backend, or a block/table cache.
- The full SQL standard, a cost-based optimizer, stored procedures, views.
- Authentication, TLS, multi-tenancy, or any production-deployment concern.
- Competitive performance. The numbers are for understanding, and they are reported beside
  RocksDB's and SQLite's so their scale is honest.

## 6. The system

```
psql · JDBC · the demo app
        │  PostgreSQL wire protocol
shale-server   sessions, protocol, statement routing
shale-db       SQL parser · binder · rule-based planner · Volcano executor
               serializable transactions (OCC over snapshots) · catalog
               record layer: order-preserving keys, rows, primary + secondary indexes
               cost accounting: per-statement storage statistics
        │  StorageBackend SPI (byte keys and values, WriteBatch, Snapshot)
shale-core     WAL · skiplist memtable · SSTables · manifest · compaction · bloom filters
```

## 7. Milestones

Strictly ordered; each ends in a tested, tagged artifact. Details, estimates and cut lines are in
the [completion plan](completion-plan.md); what shipped is in the [changelog](../../CHANGELOG.md).

| | Milestone | Yields |
|---|---|---|
| M0–M4 | *done* | SPI, key encoding, WAL, skiplist memtable, SSTables + flush, merge iterator |
| M5 | Manifest + recovery | the live file set as durable metadata; safe file deletion |
| M5.5 | Concurrent write path | fsync outside the lock, group commit, background flush, write stalls |
| M6 | Compaction | leveled, then size-tiered; amplification counters |
| M7 | Batches, snapshots, bloom filters | atomic multi-key writes, consistent reads, filtered lookups, per-operation statistics |
| M8 | Engine benchmarks | the engine measured, beside RocksDB — **Shale 1.0** |
| D1 | Record layer | typed rows, primary and secondary indexes, catalog |
| D2 | SQL | parser, binder, planner, executor, `EXPLAIN` |
| D3 | Joins and aggregates | nested-loop and index joins, `GROUP BY`, top-N |
| D4 | Transactions | serializable optimistic concurrency control |
| D5 | PostgreSQL protocol | `psql` and JDBC connect |
| D6 | Cost accounting | `EXPLAIN ANALYZE` down to the disk |
| D7 | Demo and findings | the CRUD app, the SQL-level measurements, **v1.0** |

## 8. References

**Spine.** Petrov, *Database Internals* (Part I); Luo & Carey, "LSM-based Storage Techniques: A
Survey" (VLDB J. 2020); CMU 15-445 (Pavlo).

**Storage.** O'Neil et al., "The Log-Structured Merge-Tree" (1996); LevelDB source and
`doc/table_format.md`; the RocksDB wiki (compaction, write stalls, PerfContext); Dayan et al.,
"Monkey" (SIGMOD 2017); Athanassoulis et al., "The RUM Conjecture" (EDBT 2016); skyzh, *mini-lsm*.

**Relational layer.** Matsunobu et al., "MyRocks" (VLDB 2020); Taft et al., "CockroachDB"
(SIGMOD 2020); the FoundationDB tuple layer; Graefe, "Volcano" (1994); Selinger et al., "Access
Path Selection" (1979); Kung & Robinson, "Optimistic Methods for Concurrency Control" (1981);
Berenson et al., "A Critique of ANSI SQL Isolation Levels" (1995); the PostgreSQL frontend/backend
protocol documentation.
