# D6 — Cost accounting: `EXPLAIN ANALYZE` down to the disk: implementation plan

**Status:** planned; starts after D5 is tagged. **Depends on:** M7 (`OperationStats`,
`WriteResult`), M6 (compaction-debt counters), D2–D5. **This is the milestone the project exists
for** (charter §1): every statement's storage cost becomes visible, from `psql`.

**Goal:** `EXPLAIN ANALYZE <statement>` runs the statement and prints its plan. Each operator
shows its rows, time and storage cost; the statement shows the write-side cost it created. Engine
state is queryable in SQL through two system tables, so the demo's engine panel needs no side
channel.

## Decisions required in the ADR ("Per-statement cost accounting")

1. **Where the numbers come from.** Each operator owns an `OperationStats` (M7) and passes it in
   `ReadOptions` for every engine call it makes. The statement's write side comes from the
   `WriteResult` of its commit: WAL bytes, group size, whether it forced. Nothing is sampled;
   every number is counted.
2. **What each operator reports.** Rows, time (from the injected `Clock`), memtables and SSTables
   probed, filter skips, blocks and bytes read, shadowed versions and tombstones skipped.
3. **What the statement reports.**
   - **Index maintenance:** keys written, per index.
   - **Uniqueness checks:** lookups, and how many a filter answered.
   - **WAL:** bytes written; the fsync ("forced, shared with *N* commits", or "not forced:
     `synchronous_commit = off`").
   - **Tombstones** created.
   - **Estimated compaction debt:** the statement's bytes written × the engine's measured write
     amplification at the time. It is labelled an estimate, and the formula is documented.
4. **Output.** One text column named `QUERY PLAN`, one row per line, as PostgreSQL does, so
   `psql` renders it natively. `EXPLAIN (ANALYZE, TIMING OFF)` drops times for reproducible
   output, as in PostgreSQL.
5. **System tables** (read-only, virtual, in the catalog):
   - `shale_levels` (level, files, bytes, compaction debt);
   - `shale_metrics` (name, value): write, read and space amplification, flushes, compactions,
     stalls, commits, aborts.
6. **Overhead.** With no `EXPLAIN`, operators pass a null `OperationStats`. A JMH benchmark shows
   the difference is within noise, and that number is in the changelog.

## Scope

**In D6:** `dev.shale.db.cost` (the statement cost record and the renderer); per-operator stats
wiring in `dev.shale.db.exec`; `EXPLAIN ANALYZE` and `EXPLAIN (ANALYZE, TIMING OFF)` in the
grammar; the two system tables; the overhead benchmark.

**Not planned:** a cost-based optimizer using these numbers (charter non-goal), per-query history
tables, sampling.

## Task order (TDD; each task one commit, gate green)

1. ADR, with a worked example of the full output for one `INSERT` and one `SELECT`; ADR index.
2. The cost record and renderer; golden-text tests.
3. Per-operator `OperationStats` wiring; write-side capture from `WriteResult`; index-maintenance
   and uniqueness-check accounting in the record layer.
4. Compaction-debt estimate from the M6 counters, with its formula in the Javadoc (N9: RocksDB's
   pending-compaction-bytes estimate).
5. `shale_levels` and `shale_metrics`.
6. **Deterministic scenario tests:** fixed options and the deterministic flush and compaction
   executor, so every count is exact. Examples:
   - an `INSERT` into a table with two secondary indexes writes exactly 3 keys and performs
     1 uniqueness lookup;
   - after deleting half a table, a full scan reports the tombstones it skipped;
   - with filters on, a missing-key lookup reports skips and no block reads.
7. Overhead benchmark.
8. Docs: `architecture/d6-cost-accounting.md` (one statement traced from the SQL to the fsync,
   with the counters marked on the diagram), README status, changelog, tag `d6-cost`.

## Acceptance gates

- **Scenario golden outputs** match exactly with `TIMING OFF`.
- **Invariants:**
  - per-operator blocks read sum to the statement's total;
  - WAL bytes equal the encoded batch size;
  - keys written equal 1 + the number of secondary indexes, for every single-row `INSERT` in the
    differential test.
- `psql` shows `EXPLAIN ANALYZE` output without any client-side change.
- Stats-off overhead is within benchmark noise.

## References

PostgreSQL documentation, "Using EXPLAIN" (`ANALYZE`, `BUFFERS`, `WAL`, `TIMING OFF`); RocksDB
wiki, "PerfContext and IOStatsContext" and the compaction-debt estimate; CockroachDB documentation,
"EXPLAIN ANALYZE"; Matsunobu et al., "MyRocks" (VLDB 2020) on secondary-index costs on an LSM.
