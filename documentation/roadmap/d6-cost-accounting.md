# D6 — Cost accounting: `EXPLAIN ANALYZE` down to the disk: implementation plan

**Status:** planned; starts after D5 is tagged. **Depends on:**
- M7: `OperationStats` in `ReadOptions`; `WriteResult` from `writeAsync`;
- M6: the `level.<n>.*` gauges and `compaction.debt.bytes`;
- D2–D5: operators, `Session`, `NoticeResponse`.

**ADR:** 0022. **Estimate:** 2 focused weeks, in 6 steps.

**This is the milestone the project exists for** (charter §1). Every statement's storage cost
becomes visible from `psql`.

**Goal:**
- `EXPLAIN ANALYZE <statement>` runs the statement and prints its plan, with each operator's rows,
  time and storage reads.
- The statement's write-side cost is printed too.
- A session setting streams a one-line cost summary after every statement.
- Two system tables expose the engine's shape to SQL.

---

## 1. Where the code will be at the start (after D5)

- `EXPLAIN` prints plans (D2 §2.5).
- Operators call `Table` methods, which call the engine with `ReadOptions.DEFAULT`.
- The engine emits counters and gauges to the `Metrics` it was opened with.
- Commits get a `WriteResult` from `writeAsync` (D4 §2.2).
- The server can send `NoticeResponse`.

## 2. The design (decided — ADR-0022, "Per-statement cost accounting", records it)

### 2.1 Where every number comes from — all counted, nothing sampled

**Reads, per operator:**
- Every operator that touches storage owns an `OperationStats` and passes it in `ReadOptions` to
  every engine call it makes, through `Table` (which takes it as a parameter).
- Reported per operator:
  - rows produced and time (from the injected `Clock`);
  - memtables and tables probed, filter skips;
  - blocks and bytes read;
  - versions, tombstones and newer-than-snapshot entries skipped.

**Writes, per statement** — `Table` adds to a `StatementCost` passed down from the `Session`:
- keys written, per index (the primary index plus each secondary index);
- uniqueness lookups, and how many a bloom filter answered;
- tombstones written;
- the bytes staged.

**Commit (the statement that commits, or `COMMIT` itself)** — from `WriteResult`:
- WAL bytes;
- whether the group synced, and how many transactions shared that sync (`groupSize`);
- the engine sequence span.

**Estimated compaction debt created** = the bytes staged × the engine's current write
amplification, from `bytes.flush.written` + `bytes.compaction.written` over `bytes.user.written`.
It is always labelled an *estimate*, and the formula is in `CompactionDebtEstimate`'s Javadoc,
citing M6's debt estimate.

**Inside an explicit transaction** a statement's writes reach the engine only at `COMMIT`. So its
`EXPLAIN ANALYZE` reports the staged costs, plus the line `write-ahead log and fsync: reported at
COMMIT`. `COMMIT`'s cost notice then carries them. In autocommit every line is filled.

### 2.2 How the engine's state reaches SQL, without a new engine API

`Database` opens the engine with its own `MetricsRegistry` (a `Metrics` that keeps the latest
counter and gauge values in a map, and forwards to the caller's `Metrics` if one was given).
Everything below reads that registry.

### 2.3 `EXPLAIN ANALYZE` output

- **Grammar:** `EXPLAIN ANALYZE <statement>` and `EXPLAIN (ANALYZE, TIMING OFF) <statement>`
  (times omitted, so the output is reproducible).
- **It executes the statement,** DML included, exactly as PostgreSQL's does; documented, with the
  usual advice to wrap it in `BEGIN … ROLLBACK`.
- **The format:** one `QUERY PLAN` text column, one row per line, so `psql` prints it natively.
  - Each plan node's line gains `rows=` and `time=`.
  - An indented `storage:` line lists its non-zero counters in a fixed order.
  - After the tree, a `statement:` block lists the write side and the commit.

  For example (`TIMING OFF`):

  ```text
  Insert table=books rows=1
    storage: memtables_probed=2 tables_probed=1 filter_skips=3 blocks_read=1
  statement:
    keys_written=3 (books_pkey=1 books_by_author=1 books_by_isbn=1)
    uniqueness_lookups=2 (answered_by_filter=1)
    wal_bytes=212 fsync=shared(4) sequence=10841..10843
    estimated_compaction_debt=1.9 KiB (write amplification 8.7)
  ```

### 2.4 Cost notices

- **`SET shale.cost_notices = on`** makes the session send, after each statement, a
  `NoticeResponse` (severity `NOTICE`, code `00000`) with a one-line summary:

  ```text
  cost: rows=1 keys_written=3 wal_bytes=212 fsync=shared(4) tables_probed=1 debt≈1.9KiB
  ```

- `psql` prints notices as they arrive; pgjdbc exposes them through `Statement.getWarnings()`.
  That is how D7's application shows every statement's cost without running anything twice.
- **Off by default.** With it off, operators pass `null` stats (M7's no-cost path).

### 2.5 System tables (read-only, virtual, in the catalog at reserved ids below 1000)

| Table | Columns | Source |
|---|---|---|
| `shale_levels` | `level BIGINT, files BIGINT, bytes BIGINT, compaction_debt BIGINT` | the `level.<n>.*` and debt gauges |
| `shale_metrics` | `name TEXT, value BIGINT` | every registry entry, plus the derived write and space amplification (×1000, as BIGINT) |
| `shale_verify` | `problem TEXT` | runs `Database.verify()` when scanned; no rows means consistent. It is how a client of the wire protocol — D7's crash demo — checks consistency |

- They are **scanned like tables**, with `TableScan` only. They can be filtered, ordered and
  joined.
- **They are read-only:** an `INSERT`, `UPDATE` or `DELETE` on one → `0A000`.

## 3. New and changed types

| Type | Package | Step |
|---|---|---|
| `MetricsRegistry` | `dev.shale.db` | 2 |
| `StatementCost`, `CompactionDebtEstimate`, `CostRenderer` | `dev.shale.db.cost` | 3, 4 |
| operators: an `OperationStats` per storage operator; `Table` methods take stats and a `StatementCost` | `dev.shale.db.exec`, `dev.shale.db.table` | 3 |
| `ExplainAnalyze` (runs the plan, collects, renders) | `dev.shale.db.plan` | 4 |
| `SystemTables` (`shale_levels`, `shale_metrics`, `shale_verify`) | `dev.shale.db.catalog` | 5 |
| `Session` (cost notices setting); `Connection` (sends the notice) | `dev.shale.db`, `dev.shale.server` | 5 |

## 4. Steps

### Step 1 — ADR-0022 (`adr/0022-cost-accounting`), ~1 day
`docs(cost)`: records §2, with the worked example of §2.3 for one `INSERT` and one `SELECT`.
Alternatives:
- thread-local statistics (rejected: operators share threads);
- a new engine statistics API (rejected: the `Metrics` sink already carries it);
- sampling (rejected: exact counts are the point).

**Done when:** merged.

### Step 2 — the registry (`d06/registry`), ~1 day
`feat(cost)`: `MetricsRegistry`; `Database` opens the engine with it. `MetricsRegistryTest`: the
latest values; forwarding. **Done when:** green.

### Step 3 — the plumbing (`d06/plumbing`), ~3 days
`feat(exec)`, `feat(table)`: per-operator `OperationStats`; `Table` accepts stats and
`StatementCost`; index-maintenance and uniqueness accounting; commit fills in `WriteResult`
fields. `StatementCostTest`, with exact expected values on a deterministic engine (`ManualExecutor`,
tiny sizes, fixed seed):
- an `INSERT` into a table with two secondary indexes writes exactly 3 keys and does exactly 2
  uniqueness lookups;
- a `DELETE` of 10 rows writes 10 × (1 + indexes) tombstones.

**Done when:** exact matches.

### Step 4 — `EXPLAIN ANALYZE` (`d06/explain-analyze`), ~3 days
`feat(plan)`: the grammar, `ExplainAnalyze`, `CostRenderer`, `CompactionDebtEstimate`. Tests:
- **Golden outputs** (`explain_analyze.slt`, `TIMING OFF`) for:
  - a point lookup over two memtables and three tables;
  - a missing-key lookup with filters (skips, and zero blocks read);
  - a full scan after deleting half the rows (the tombstones skipped);
  - a scan at a snapshot with newer versions present;
  - an `INSERT` in autocommit (every line);
  - the same `INSERT` inside `BEGIN` ("reported at COMMIT").
- **Invariants,** over the D3 differential query set:
  - the per-operator `blocks_read` sums to the statement's;
  - `wal_bytes` equals the committed batch's encoded size;
  - `keys_written` = 1 + the number of secondary indexes, for every single-row `INSERT`.

**Done when:** green.

### Step 5 — notices and system tables (`d06/notices`), ~2 days
`feat(api)`: `shale.cost_notices`; `Connection` sends the notice; `SystemTables`. Tests:
- over pgjdbc, `getWarnings()` carries one cost line per statement when on, and none when off;
- `SELECT * FROM shale_levels` after a scripted flush and compaction matches the engine's state;
- `shale_verify` returns no rows on a clean database, and a row per problem after a test
  deliberately deletes an index entry through the engine;
- writing to a system table → `0A000`.

**Done when:** green.

### Step 6 — overhead, documentation, tag (`d06/docs`), ~2 days
1. `test(bench)`: `CostOverheadBenchmark` — the D3 query set with notices off vs a build without
   D6's plumbing (the `d5-pgwire` tag). The difference must be within noise, and the numbers go in
   the changelog.
2. Docs:
   - `architecture/d6-cost-accounting.md`: one `INSERT` traced from the SQL to the fsync, with
     every counter marked where it is incremented (`file:line`);
   - glossary rows (statement cost, cost notice, system table);
   - README: the `EXPLAIN ANALYZE` example becomes the front-page demo;
   - changelog; the completion plan's status table;
   - `guides/shaledb-sql.md`, a "Seeing what a statement cost" section:
     - how to read `EXPLAIN ANALYZE`, line by line;
     - cost notices, and how to turn them on;
     - the system tables, with a query for each;
   - the FAQ's cost answer loses "planned".
3. **Reconciliation pass for D7.** Tag `d6-cost`.

## 5. Milestone acceptance gates

- Golden `EXPLAIN ANALYZE` outputs match exactly with `TIMING OFF`.
- Invariants tie per-operator totals to statement totals, over the differential query set.
- `psql` and pgjdbc receive cost notices with no client change.
- Overhead with cost reporting off is within benchmark noise.

## 6. Not in D6

A cost-based optimizer using these numbers; query history tables; sampling; `EXPLAIN (BUFFERS)`
or `(FORMAT JSON)`.

## References

PostgreSQL documentation, "Using EXPLAIN" (`ANALYZE`, `BUFFERS`, `WAL`, `TIMING OFF`) and "Messages
and Error Reporting" (notices); RocksDB wiki, "PerfContext and IOStatsContext";
`EstimatedPendingCompactionBytes`; CockroachDB documentation, "EXPLAIN ANALYZE"; Matsunobu et al.,
"MyRocks" (VLDB 2020) on the cost of secondary indexes on an LSM.
