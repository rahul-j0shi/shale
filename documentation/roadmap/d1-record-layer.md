# D1 — Record layer: encoding, rows, catalog, transactions: implementation plan

**Status:** planned; the first ShaleDB milestone (ADR-0013). Starts after M8 (or after M7 on the
fast-track). **Depends on:** `shale-core`'s public API only — `StorageBackend`, `WriteBatch`,
`Snapshot`, `ReadOptions` (M7), `Env` and `FaultInjectionEnv` (M5, test fixtures). **Creates:**
`shale-db`. **ADR:** 0018. **Estimate:** 2–3 focused weeks, in 8 steps.

**Goal.** Store typed tables with primary and secondary indexes on the byte-key engine, behind an
embedded Java API. A row and all its index entries commit in one `WriteBatch`; every read runs at
one snapshot. SQL arrives in D2. D1 also builds `Transaction` — its read-your-own-writes view and
its statement boundaries — under a simple writer lock. D4 later replaces that lock with
optimistic validation without renaming anything.

---

## 1. Where the code will be at the start (after M8)

- **Engine:** complete through M7 — batches, snapshots, filters.
- **Modules:** no `shale-db` yet.
- **Build checks:** `verifyNoRuntimeDependencies` exists only in `shale-core/build.gradle.kts`.
  `verifyModuleGraph` (M8) allows `shale-bench → shale-core`.

## 2. The design (decided — ADR-0018, "Relational data layout", records it)

### 2.1 Types and values

- **Four column types:** `BIGINT` (Java `long`), `DOUBLE`, `TEXT` (UTF-8), `BOOLEAN`, and `NULL`.
- **`Value`** is a sealed interface with records `BigintValue`, `DoubleValue`, `TextValue`,
  `BooleanValue`, and a singleton `NullValue`.
- **Canonical order** (`Value.compareTo`, used by every sort, comparison and index, so they can
  never disagree):
  - **BIGINT:** numeric.
  - **DOUBLE:** numeric, with −0.0 = 0.0 and NaN greater than every number (PostgreSQL's rule).
  - **TEXT:** by Unicode code point, which is UTF-8 byte order. **Not** `String.compareTo`, which
    orders by UTF-16 code unit and puts supplementary characters (emoji) in a different place
    than the index does.
  - **BOOLEAN:** false < true.
  - **NULL:** sorts **after** every non-null value, as PostgreSQL's default `NULLS LAST` does for
    ascending order.

### 2.2 Order-preserving key encoding (`OrderedEncoding`, after the FoundationDB tuple layer)

Each value is a tag byte followed by its body. Bytewise order of the encodings equals `Value`
order.

| Value | Tag | Body |
|---|---|---|
| `BOOLEAN` false / true | `0x10` / `0x11` | none |
| `BIGINT` | `0x20` | 8 bytes big-endian, sign bit flipped |
| `DOUBLE` | `0x30` | IEEE-754 bits, big-endian; if negative invert every bit, else flip the sign bit. −0.0 is written as 0.0; every NaN as the canonical NaN |
| `TEXT` | `0x40` | UTF-8 with each `0x00` written as `0x00 0xFF`, then the terminator `0x00 0x01` |
| `NULL` | `0xFE` | none — the highest tag, so NULL sorts last |

- **Composites.** A composite key is the concatenation of its encoded columns. Every body is
  either fixed-length or terminated, so no encoding is a prefix of another's continuation.
- **Why big-endian here.** ADR-0005's little-endian rule governs the engine's own formats. Keys
  are big-endian because order demands it; ADR-0018 records the exception.
- **Not planned:** descending-order encodings.

### 2.3 Keyspaces

| Key | Value | Meaning |
|---|---|---|
| `0x01 'D' tableId:u32BE` | descriptor (§2.5) | a table's descriptor, with its indexes |
| `0x01 'N' enc(name)` | kind (`'T'` table or `'I'` index), `tableId:u32BE` | name → owning table |
| `0x01 'S'` | `nextId:u32BE` | the id counter; ids are never reused |
| `0x01 'G' tableId:u32BE` | empty | a dropped table whose data is still being deleted |
| `0x02 tableId:u32BE indexId:u32BE …` | see below | table data |

- **The primary index** has `indexId` 1: key = `0x02 t 1 enc(pk columns)`; value = the row (§2.4).
- **A non-unique secondary index:** key = `0x02 t i enc(index columns) enc(pk columns)`; the
  value is empty.
- **A unique secondary index:** key = `0x02 t i enc(index columns)`; value = `enc(pk columns)`.
  But when **any** indexed column is NULL, the entry uses the non-unique layout (pk appended,
  empty value), because SQL allows any number of NULLs in a unique column.
  - **Reading an entry:** decode the index columns; if any is NULL, the primary key follows in
    the key; otherwise it is the value.
- **Table ids** start at 1000. Ids below that are reserved.

### 2.4 Row format v1 (the primary index's value)

`formatVersion (1 byte = 1)`, then `columnCount` (varint32: how many **non-key** columns the
table had when the row was written, *n*), then a null bitmap over those *n* columns (⌈n/8⌉ bytes,
least significant bit first), then each non-null non-key column in schema order:

| Type | Encoding |
|---|---|
| `BIGINT`, `DOUBLE` | 8 bytes little-endian |
| `BOOLEAN` | 1 byte |
| `TEXT` | varint32 length + UTF-8 bytes |

- **No per-row CRC.** The engine checksums every WAL record and SSTable block that carries a
  row; ADR-0018 records that as the exception to `on-disk-formats.md` §2.
- **Why `columnCount`: schema evolution without rewriting.** Columns are only ever appended (§2.5),
  so a row written before `ALTER TABLE … ADD COLUMN` (D2) is simply shorter. When decoding with
  a descriptor of *m* non-key columns:
  - columns *n* to *m*−1 read as NULL;
  - *n* > *m* is `CorruptionException`, since columns are never removed.

  Adding a column is then one descriptor write, however large the table. That is PostgreSQL's
  design: `natts` in the tuple header, and "missing" attributes read as NULL.
  - **Cost:** one byte per row below 128 columns.
  - **Why now:** it is decided in v1 because adding it later would be a format change (N2) that
    every existing row would need.
- **Decoding** still validates structure: bitmap size, lengths within the value, no trailing
  bytes. A failure is `CorruptionException` naming the table and key.

### 2.5 The catalog

- **Descriptors.** `TableDescriptor(id, name, columns, primaryKey, indexes, state)` with
  `ColumnDescriptor(name, type, notNull)` and `IndexDescriptor(id, name, columns, unique, state)`.
  A `DescriptorCodec` writes them as versioned binary, specified in `catalog/format.md`.
- **States:** `PUBLIC`, or `BACKFILLING` (indexes only).
- **Names** are lowercase ASCII identifiers (`[a-z_][a-z0-9_]*`, at most 63 characters). D2
  lowercases unquoted SQL identifiers. Table and index names share one namespace, database-wide,
  as PostgreSQL's relations do. The name entries (`0x01 'N'`) cover both; an index's entry names
  its table.
- **Column positions** are stable. A table's columns keep their order for its lifetime. New
  columns are appended, and none is ever dropped or reordered (no `DROP COLUMN`), so a row's
  *i*-th non-key column always means the same column.
- **`Catalog`** is an in-memory cache, loaded at open and replaced after each DDL commits. DDL
  runs one at a time, under the database's DDL lock.
  - **`Catalog.version()`**, a `long` incremented by every DDL, lets D2's prepared statements
    detect that the schema they were bound against has changed.
- **Creating an index** (after F1's online schema change, simplified):
  1. one batch writes the descriptor with the index `BACKFILLING`;
  2. batches of 1,000 entries fill it from a snapshot of the table;
  3. a final batch flips it to `PUBLIC`.

  The planner uses only `PUBLIC` indexes. DDL holds the DDL lock *and* the writer lock (§2.6)
  for its whole duration, so no DML runs concurrently in D1–D3.
  **At open:** a `BACKFILLING` index is dropped. Its `CREATE INDEX` was never acknowledged, so
  dropping it is the correct recovery.
- **Dropping** a table or an index:
  1. one batch removes the descriptor (and name) and writes the `0x01 'G'` marker;
  2. batches of point deletes remove the data;
  3. a final batch removes the marker.

  **At open:** every `G` marker's deletion is resumed. The sweep is bounded by what was dropped,
  never the whole database.

### 2.6 `Transaction` — the unit every read and write goes through

- **Begin** takes an engine `Snapshot`; in D1–D3 it also takes the database's **writer lock**
  (one read-write transaction at a time). Read-only transactions take no lock.
- **Two overlay layers,** each a `TreeMap<byte[], byte[]>` ordered bytewise, where a `TOMBSTONE`
  marker value means deleted:
  - **`committedWrites`:** writes of earlier statements in this transaction;
  - **`statementWrites`:** writes of the statement now running.
- **Reads** (`get`, `scan`) see the snapshot merged with `committedWrites` **only**. The running
  statement never reads its own writes. That rules out the Halloween problem by construction: an
  `UPDATE` that moves rows forward in an index cannot meet them again.
- **Uniqueness checks** look in all three: the snapshot, `committedWrites` and `statementWrites`.
  So two rows with one primary key in the same `INSERT` fail.
- **Statement boundaries.** `endStatement()` merges `statementWrites` into `committedWrites`;
  `abortStatement()` discards them.
- **Commit** builds one `WriteBatch` from `committedWrites` and calls `engine.write(batch,
  durability)`, with a `// DURABILITY:` line. Then it releases the snapshot and the lock.
  **Rollback** releases them.
- **The merged cursor** (`OverlayCursor`): a two-way merge by key of the engine cursor and the
  overlay's sub-map. On equal keys the overlay wins, and an overlay tombstone hides the key.

### 2.7 The table API (what D2's executor calls)

```java
public final class Database implements AutoCloseable {
  public static Database open(Path dir, DatabaseOptions options);
  public static Database open(Path dir, DatabaseOptions options, Clock clock, Metrics metrics, Env env);
  public Transaction begin(boolean readOnly, Durability durability);
  public Catalog catalog();
  public TableDescriptor createTable(TableSpec spec);        // DDL: autocommit, DDL lock
  public void dropTable(String name);
  public IndexDescriptor createIndex(IndexSpec spec);
  public void dropIndex(String index);                      // names are database-wide
  public TableDescriptor addColumn(String table, ColumnSpec column);  // nullable only; §2.4
  public VerifyReport verify();                              // §2.8
}
public final class Table {                                   // obtained as txn.table(name)
  public void insert(Row row);                               // uniqueness checked; staged
  public void update(Row oldRow, Row newRow);                // index entries maintained; pk may change
  public void delete(Row row);
  public Row get(Row.Key primaryKey);                        // null if absent
  public RowCursor scanPrimary(KeyRange range);              // primary-key order
  public RowCursor scanIndex(String index, KeyRange range);  // index order, then pk
}
```

- **`Row`** is the column values in schema order. **`KeyRange`** is an inclusive/exclusive range
  of encoded key prefixes, built by `KeyRange.of(...)` from bound `Value`s.
- **Failures:**
  - a duplicate key → `ConstraintViolationException` (SQLSTATE `23505`);
  - a NULL in a `NOT NULL` column → SQLSTATE `23502`;
  - an encoded key over 16 KiB or a row over 16 MiB (the engine's limits, M7) → SQLSTATE `54000`
    (`program_limit_exceeded`), naming the table and the limit.
- **`addColumn`** writes the new descriptor in one batch: no row is touched.
  - **A `NOT NULL` column** is rejected with `0A000`: existing rows would violate it, and there are
    no defaults to fill them.
  - **A duplicate name** is `42701`.
- **`DatabaseOptions`** holds the engine's `ShaleOptions`, plus `queryMemoryBytes` for D2
  (default 64 MiB).

### 2.8 `Database.verify()`

- **What it checks:**
  - every primary row decodes;
  - every row has exactly its expected index entries;
  - every index entry points to an existing row whose indexed values match it;
  - no data exists under an id with no descriptor, unless a `G` marker names it.
- **How:** one pass per index at one snapshot. It returns a `VerifyReport` listing each problem;
  tests assert that it is empty.

## 3. New types and files (all in `shale-db`)

| Item | Package | Step |
|---|---|---|
| module build, `settings.gradle.kts` entry, shared `verifyNoRuntimeDependencies`, `verifyModuleGraph` edge `shale-db → shale-core` | root and `shale-db/build.gradle.kts` | 2 |
| `Value` and its records, `ColumnType`, `Row`, `Row.Key` | `dev.shale.db.type` | 3 |
| `OrderedEncoding`, `RowCodec`, `KeyBuilder`, `KeyRange`, `record/format.md` | `dev.shale.db.record` | 3, 4 |
| `TableDescriptor`, `ColumnDescriptor`, `IndexDescriptor`, `DescriptorCodec`, `Catalog`, `catalog/format.md` | `dev.shale.db.catalog` | 5 |
| `Transaction`, `OverlayCursor` | `dev.shale.db.txn` | 6 |
| `Database`, `DatabaseOptions`, `Table`, `RowCursor`, `TableSpec`, `IndexSpec`, `VerifyReport`, `ConstraintViolationException` | `dev.shale.db`, `dev.shale.db.table` | 6, 7 |

## 4. Steps

### Step 1 — ADR-0018 (`adr/0018-relational-layout`), ~1 day
`docs(record)`: records §2. Alternatives:
- NULL-first encoding (rejected: PostgreSQL orders NULLS LAST, and clients speak its protocol);
- self-describing catalog tables à la `pg_class` (rejected: much more machinery than descriptors
  plus a name index);
- a per-row CRC (rejected: the engine already checksums the bytes that carry it);
- restarting a half-built index (rejected: dropping is correct, since it was never acknowledged).

**Done when:** merged.

### Step 2 — the module (`d01/module`), ~1 day
1. `build(build)`: move `verifyNoRuntimeDependencies` into the root build. It is registered for
   each project in a `zeroDependencyModules` list (`shale-core`, `shale-db`, later
   `shale-server`).
2. `build(build)`: `shale-db` in `settings.gradle.kts`; `shale-db/build.gradle.kts` (`api` on
   `shale-core`, `testImplementation(testFixtures(project(":shale-core")))`); the
   `verifyModuleGraph` edge; `package-info`s.
3. `docs(docs)`: CLAUDE.md §2 and `project-scope.md` show the module as existing.

**Done when:** `build` is green with an empty module.

### Step 3 — values and the key encoding (`d01/encoding`), ~3 days
1. `docs(record)`: `record/format.md` — §2.2 and §2.3 with a worked hex example of a two-column
   key containing a NULL and a TEXT holding a `0x00`.
2. `feat(record)`: `Value` and its records, `OrderedEncoding`, `KeyBuilder`, `KeyRange`.
3. **Tests:**
   - `OrderedEncodingPropertyTest` (jqwik): for generated a, b of each type, and composites,
     `signum(compareBytes(enc(a), enc(b))) == signum(a.compareTo(b))`. Edge generators: NULL,
     ±0.0, NaN, ±infinity, `Long.MIN_VALUE`/`MAX_VALUE`, empty text, text with `0x00`, emoji.
   - `ValueCompareTest`: TEXT order is code-point order where `String.compareTo` disagrees
     (`"￿"` vs `"😀"`).
   - Golden `golden/record/v1/keys.bin` and its `.json`; decode rejects a missing terminator, an
     unknown tag, a short body.

**Done when:** green.

### Step 4 — the row format (`d01/row-format`), ~2 days
1. `docs(record)`: §2.4 in `format.md`, with a worked example.
2. `feat(record)`: `RowCodec`.
3. **Tests:** round-trip property over random schemas and rows; golden `golden/record/v1/row.bin`;
   bit-flip at every offset of the golden (detected, or decodes to a row that the test then
   rejects — never a crash or an out-of-bounds read); trailing bytes rejected;
   - a row written with *n* columns, decoded against a descriptor of *n* + 2, reads the two new
     columns as NULL;
   - `columnCount` greater than the descriptor's → `CorruptionException`.

Commit with `Format-Change: row v1 — new` and `Reversible: no`. **Done when:** green.

### Step 5 — the catalog (`d01/catalog`), ~3 days
1. `docs(catalog)`: `catalog/format.md` for `DescriptorCodec`.
2. `feat(catalog)`: descriptors, codec, `Catalog`, id allocation, name validation.
3. **Tests:** codec round-trip property and golden; `Catalog` reload after reopen; ids are never
   reused after a drop; invalid names are rejected.

**Done when:** green.

### Step 6 — transactions and tables (`d01/tables`), ~4 days
1. `feat(txn)`: `Transaction` (§2.6), `OverlayCursor`.
2. `feat(table)`: `Database`, `Table` (§2.7), with index maintenance on insert, update and delete,
   and uniqueness checks.
3. **Tests:**
   - `OverlayCursorTest`: interleavings, tombstones hiding snapshot keys, empty sides.
   - `TransactionTest`:
     - a statement does not see its own writes;
     - the next statement does;
     - `abortStatement` discards;
     - commit writes one batch (checked through `WriteResult`'s sequence span);
     - rollback writes nothing.
   - `TableTest`:
     - CRUD;
     - a primary-key update moves the row and all its index entries;
     - duplicate primary key / unique value → `23505`, nothing staged;
     - many NULLs in a unique index are allowed;
     - `NOT NULL` → `23502`;
     - scans in key order, including NULLs last.

**Done when:** green.

### Step 7 — DDL, index builds and drops, `verify` (`d01/ddl`), ~3 days
1. `feat(table)`: `createTable`, `dropTable`, `createIndex` (§2.5), `dropIndex`, `addColumn`, the
   open-time recovery of `BACKFILLING` indexes and `G` markers, `verify()`.
2. **Tests:** `DdlTest`:
   - create an index on a filled table, then `verify()`;
   - drop, then recreate under the same name: the new table gets a new id;
   - reopen after a drop: no data is left under the old id;
   - `addColumn` on a table of 10,000 rows writes one batch (checked through `WriteResult`).
     Old rows then read the column as NULL, new rows store it, and an index on the new column
     can be built and `verify()`s.

**Done when:** green.

### Step 8 — harnesses, documentation, tag (`d01/harness`), ~3 days
1. `test(table)`: `RecordModelTest` — random inserts, updates, deletes and index creations on
   three tables. The oracle is a list of rows per table; `verify()` and full comparisons run after
   every restart.
2. `test(table)`: `RecordCrashTest` (tag `crash`) — `FaultInjectionEnv` crash at every operation
   of:
   - a two-index insert;
   - a primary-key update;
   - an index backfill of 3 batches;
   - a table drop.

   After power loss and reopen:
   - each transaction is whole or absent;
   - a `BACKFILLING` index is gone;
   - a drop is complete;
   - `verify()` is clean.
3. Docs:
   - `architecture/d1-record-layer.md` (the table → key-value mapping diagram, one row's bytes end
     to end, the overlay);
   - glossary rows (`Catalog`, `TableDescriptor`, `Row`, `PrimaryIndex`, `SecondaryIndex`,
     order-preserving encoding, `Transaction`, overlay);
   - README status, changelog, the completion plan's status table.
4. **Reconciliation pass for D2.** Tag `d1-record`.

## 5. Milestone acceptance gates

- **Order:** encoding order = `Value` order, for every type and composite (property test).
- **Atomic rows:** no crash point leaves an index entry without its row, or a row missing an
  entry; `verify()` is clean after every recovery.
- **DDL recovery:** a half-built index is dropped; a half-done drop finishes.
- **Constraints:** duplicates and NULL violations are rejected before anything is staged.
- **Formats:** goldens and bit-flip tests for keys, rows and descriptors.
- `shale-db`'s runtime dependency graph is empty; `verifyModuleGraph` passes.

## 6. Not in D1

SQL (D2); optimistic concurrency (D4 replaces the writer lock); `ALTER TABLE` statements (D2 adds
`ADD COLUMN`; the row format supports it from v1); descending encodings; range tombstones.

## References

FoundationDB "Tuple layer" specification; CockroachDB "Structured data encoding in CockroachDB
SQL" and Taft et al. (SIGMOD 2020); Matsunobu et al., "MyRocks" (VLDB 2020); Rae et al., "Online,
Asynchronous Schema Change in F1" (VLDB 2013); PostgreSQL documentation, "Sorting Rows" (`NULLS
LAST`); the Halloween problem (Selinger's System R account, via Gray & Reuter); Petrov, *Database
Internals* ch. 1 and 3.
