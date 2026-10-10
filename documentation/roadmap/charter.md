# Charter — ShaleDB, a see-through database

The purpose, the design choices that follow from it, what is in and out of scope, and how
success is judged. The decision behind it is [ADR-0013](../adr/0013-shaledb-see-through-database.md);
the working order of milestones is the [completion plan](completion-plan.md).

## 1. Why this exists

**A database's real costs are hard to see, and hardest on LSM storage.** LSM engines now sit
under most write-heavy databases (RocksDB under MyRocks and TiKV; Pebble, a Go LSM modelled on
RocksDB, under CockroachDB; Cassandra; ScyllaDB), and their costs are deferred and indirect:
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
fsync it shared, and its estimated compaction debt. A browser view draws the same numbers as the
statement's path through the LSM (§5). Its benchmarks connect storage design choices to the
latency of SQL statements.

**Why build every layer by hand.** Existing engines *can* be instrumented, and RocksDB's
`perf_context` shows how far that goes. But a system assembled from RocksDB and a borrowed SQL
engine would give per-layer fragments, joined from the outside, across codebases no one reads end
to end. Owning every layer is what makes attribution exact *and* short enough to explain. It is
not a technical necessity. Building each layer is also the learning, and that is a reason in its
own right here, not an excuse (CLAUDE.md §1).

**Exact versus estimated.** A statement's synchronous work — probes, blocks read, WAL bytes, the
shared fsync — is counted exactly. The compaction work it defers is shared by many statements, so
it is reported as an *estimate* under a declared model (D6 §2.1), never as an exact cost.

## 2. What it claims, and how each claim is proven

| Claim | Evidence |
|---|---|
| **Visible:** every statement's storage cost can be seen | One statement trace per statement (D6), rendered as `EXPLAIN ANALYZE` text, as `FORMAT JSON`, and drawn by the demo's trace view (D7). Its numbers are checked against hand-computed golden traces |
| **Measured:** storage choices are traced to SQL latency | Benchmarks: leveled vs size-tiered compaction, bloom bits per key, memtable size, group commit — each also run through the SQL workload, with RocksDB and SQLite as reference points |
| **Durable:** no acknowledged write is ever lost | Per failure model, never one standing in for another (ADR-0024 §7): truncation at every byte (torn tail); bit flips at every bit and malformed-structure fixtures (damaged bytes, M4.5); `FaultInjectionEnv` at every file operation (simulated power loss, M5); a `kill -9` test against the running server (process death, D5); a model test against an in-memory oracle (clean restart). Physical power loss is argued from fsync ordering, not tested |
| **Correct:** transactions are serializable | Write-skew, lost-update and phantom tests; a serial-replay check over every tested schedule |
| **Usable:** it behaves like a database | `psql` and the standard PostgreSQL JDBC driver connect; a CRUD application runs on it |

## 3. Design choices that follow

| Choice | Because |
|---|---|
| **LSM storage** | Its costs are the least intuitive, so they are the most worth making visible |
| **A relational layer on the storage SPI** | Developers meet storage costs through the statements they write. The layering mirrors MyRocks and CockroachDB: tables and indexes become ordered keys |
| **PostgreSQL wire protocol** | The visibility is reachable with tools people already use; no custom client or shell |
| **One trace, drawn as well as printed** | Counters only teach someone who already knows what they mean. A picture of the path, the places and the proportions teaches the rest. Drawing it from the same trace the text comes from keeps the picture honest |
| **Single node** | The costs being explained are single-node costs. Distribution adds network and consensus costs, a different subject |
| **Hand-written, zero-dependency core** | Attribution needs every layer instrumented. The build enforces the rule, so the claim is checkable (ADR-0002) |
| **Java 25** | Most hobby storage engines are written in Go or Rust; a JVM engine shows GC-aware design and the FFM API. The ecosystem also has the standard PostgreSQL driver the demo proves against |

## 4. Goals

1. **Correctness first:** never lose or corrupt acknowledged data; property, model, crash and
   serializability tests are gates, not extras. Every guarantee names the failure model its test
   covers, and a planned guarantee is never presented as an implemented one.
2. **Visibility:** per-statement cost accounting through every layer, with the counters a reader
   needs to reason about read, write and space amplification.
3. **Measurement:** every performance statement in the README is a number with its method, not
   an adjective.
4. **Explainability:** the code reads like a book — each mechanism cites the literature it
   follows (N9), and each expensive decision has an ADR with its rejected alternatives.

## 5. The end goal: what someone sees at v1.0

**In one sentence:** pick any SQL statement, and see — as text in `psql`, and drawn in a browser
— everything it cost, from its plan down to the disk, including the work it leaves for later.

**The five-minute experience** (D7's acceptance gates make each step a test):
1. **Run it.** Clone, run `scripts/demo.sh` (or `docker compose up`), and open the Shelf
   library app. This takes five minutes or less, with only JDK 25 or Docker installed.
2. **Do something ordinary** — check out a book — and open its **trace view**. You see:
   - the transaction's statements in order, ending at `COMMIT`;
   - each statement's path: SQL → plan → operators → memtables → levels L0–L6 → WAL → the fsync
     it shared → the compaction it defers;
   - the LSM drawn with the statement's own numbers: which levels were probed, which a key range
     or a bloom filter ruled out, how many blocks were read;
   - where the bytes went, as a breakdown.

   In `psql`, `EXPLAIN ANALYZE` gives the same numbers as text. They are the same trace, so they
   cannot disagree.
3. **Compare.** Pin the trace, add two indexes or delete half the rows, and run it again. The
   cost of an index, or of a tombstone, is visible as a difference between two pictures.
4. **Break it.** `scripts/crash-demo.sh` kills the server mid-load, and nothing it acknowledged
   is lost.
5. **Ask why.** Every number links to the line of code that counts it (the tour), every design
   to its ADR, and every performance statement to a chart with its method.

**Who it is for, in order:**
- **someone learning how databases really work**, who reads the code and the docs;
- **a reviewer or interviewer**, who should be convinced by the README and its evidence in ten
  minutes;
- **a developer embedding the engine**, who needs the guide and honest limits.

**Success, checkably.** Each row is a gate in a milestone plan, not an aspiration:

| Goal | Criterion | Where it is checked |
|---|---|---|
| **Visible** | every statement can produce a trace, rendered as text, JSON and a drawing, and all three agree on every number | D6 gates; D7 gates |
| **Exact where it can be, labelled where it cannot** | synchronous costs are counted exactly; deferred compaction is drawn dashed and labelled an estimate, with its model | D6 invariants; the trace view checklist |
| **Measured** | each storage tradeoff is a chart with its method: E1–E8 for the engine, F1–F5 for SQL | M8, D7 |
| **Durable and correct** | per failure model (§2), and serializable (D4) | M4.5–D5 gates |
| **Learnable** | a newcomer goes from a fresh clone to a drawn trace in five minutes, and from any counter to its source line through the tour | D7 Steps 6–7 |
| **Honest** | no README claim without linked evidence; known defects listed openly | D7 gate; the bug log |

**Where it can stop early.** Every milestone ends in a working artifact, and four of them are
complete stopping points with their own claims (completion plan §1). A smaller result is named as
such, never presented as v1.0.

## 6. Non-goals

- Replication, sharding or distributed transactions.
- A second storage backend, or a block/table cache.
- The full SQL standard, a cost-based optimizer, stored procedures, views.
- Authentication, TLS, multi-tenancy, or any production-deployment concern.
- Competitive performance. The numbers are for understanding, and they are reported beside
  RocksDB's and SQLite's so their scale is honest.

## 7. The system

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

## 8. Milestones

Strictly ordered; each ends in a tested, tagged artifact. Details, estimates and cut lines are in
the [completion plan](completion-plan.md); what shipped is in the [changelog](../../CHANGELOG.md).

| | Milestone | Yields |
|---|---|---|
| M0–M4 | *done* | SPI, key encoding, WAL, skiplist memtable, SSTables + flush, merge iterator |
| M4.5 | Integrity boundaries | a checksum on every log header; recovery that classifies only verified bytes; validated decoders |
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
| D6 | Cost accounting | one statement trace per statement: `EXPLAIN ANALYZE` text, `FORMAT JSON`, cost notices, system tables |
| D7 | Demo and findings | the CRUD app with the trace view, the SQL-level measurements, **v1.0** |

## 9. References

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
