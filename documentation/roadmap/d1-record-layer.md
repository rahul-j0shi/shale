# D1 — Record layer: encoding, rows, catalog: implementation plan

**Status:** planned; first ShaleDB milestone (ADR-0013). Starts after M8 (or after M7 on the
fast-track). **Depends on:** M7 (`WriteBatch`, `Snapshot`, `ReadOptions`), the public
`StorageBackend` SPI only. **Creates:** the `shale-db` module.

**Goal:** store typed tables with primary and secondary indexes on the byte-KV engine. A row and
all its index entries commit in one `WriteBatch`; every read runs at one `Snapshot`. The
artifact is an embedded Java API for typed tables. SQL arrives in D2.

## Design (decided — ADR-0018, "Relational data layout", records it)

1. **Order-preserving encoding.** Encoded values must compare bytewise exactly as the values
   compare. After the FoundationDB tuple layer:
   - a type tag byte (NULL sorts first);
   - BIGINT as 8-byte big-endian with the sign bit flipped;
   - DOUBLE as IEEE-754 bits: invert all bits if negative, else flip the sign bit; −0.0
     normalised to 0.0; NaN canonical and sorting last;
   - BOOLEAN as one byte;
   - TEXT as UTF-8 with 0x00 escaped as 0x00 0xFF and terminated by 0x00 0x01.

   Descending-order columns are a stretch. Note for the ADR: ADR-0005's little-endian rule
   governs engine formats; ordering keys are big-endian because order demands it.
2. **Keyspaces.** First byte: `0x01` system (catalog), `0x02` table data. Data key =
   `0x02 | tableId:u32 BE | indexId:u32 BE | encoded index columns | encoded primary-key columns`.
   The primary index is `indexId = 1`, with row value. A non-unique secondary index entry has an
   empty value, because the primary key is already in its key. A unique secondary index has only the
   index columns in its key, and the primary key as its value (the CockroachDB layout). **Nulls in a
   unique index:** SQL allows many rows whose indexed value is `NULL`. So when any indexed column is
   `NULL`, the entry takes the non-unique layout, with the primary key appended, and it never
   collides.
3. **Row format v1.** A version byte, schema version (varint), a null bitmap, then the non-key
   columns in schema order: fixed 8-byte little-endian for BIGINT and DOUBLE, one byte for
   BOOLEAN, varint length plus bytes for TEXT. No per-row CRC: the engine checksums every WAL
   record and SSTable block that carries it. The ADR records that as the stated exception to
   `on-disk-formats.md` §2. Decoding still validates structure and throws `CorruptionException`.
4. **Catalog.** `TableDescriptor` (id, name, columns, primary key, indexes, schema version) and
   `IndexDescriptor` (id, name, columns, unique) live in the system keyspace as rows of
   hard-coded bootstrap tables, so the catalog describes itself (PostgreSQL's `pg_class` idea).
   Name→id lookup is a unique index on the catalog, and id allocation is a counter key there.
   Ids are never reused.
   - **Crash-safe index builds (after F1's online schema change).** An index is created in state
     `BACKFILLING`: writes maintain it, the planner ignores it, and the backfill runs in bounded
     batches. The final batch flips it to `PUBLIC`. Recovery finds a `BACKFILLING` index and
     restarts or drops its backfill, so a crash can never leave a half-built index serving reads.
   - **Drops.** A drop removes the descriptor in one atomic batch, then deletes data in batches.
     Data left under an id that no descriptor names is garbage, and is swept at open.
   - **Every table has a primary key**; the Java API and D2's grammar both require one.
5. **Concurrency for D1–D3.** One writer lock per `Database` serialises writes; reads run at
   snapshots without it. D4 replaces this with optimistic transactions. Stating the simple model
   now keeps D1 small and correct.

## Scope

**In D1:** module `shale-db` (package root `dev.shale.db`), JDK-only, with
`verifyNoRuntimeDependencies` applied like `shale-core`. First, move that task out of
`shale-core/build.gradle.kts` into shared build logic applied to every zero-dependency module; `dev.shale.db.type` (`Value` sealed
records, `ColumnType`); `dev.shale.db.record` (`OrderedEncoding`, `RowCodec`, key builders,
`format.md`); `dev.shale.db.catalog`; `dev.shale.db.table` — `Table` with `insert`, `get(pk)`,
`update`, `delete`, primary-key range scan and index scan; unique-constraint checks; `CREATE`/`DROP`
`TABLE`/`INDEX` as Java calls (the index states and drop sweep of decision 4); `Database.open/close`; `Database.verify()`, which
checks that every index entry matches its row and every row has all its index entries.

**Deferred:** SQL (D2); multi-statement transactions (D4). **Not planned:** `ALTER TABLE`
beyond what D2's grammar freezes; range tombstones.

## Task order (TDD; each task one commit, gate green)

1. ADR, `record/format.md` (encoding and row layout with worked hex), this plan, ADR index.
2. Module skeleton: `settings.gradle.kts`, build script, dependency check, `package-info`s.
   Update CLAUDE.md §2 and `project-scope.md` in the same commit.
3. `Value` and `OrderedEncoding` for each type; order property test; goldens.
4. `RowCodec`; round-trip property, golden, structural-corruption tests.
5. Catalog bootstrap, descriptors, id allocation; reopen tests.
6. `Table` operations with index maintenance in one `WriteBatch`; unique constraints.
7. DDL with backfill and drop; `Database.verify()`.
8. Model test: random table operations against an in-memory list-of-rows oracle, with restarts,
   `verify()` after every restart.
9. Crash test: kill at every file operation during a multi-index insert, update and delete;
   recovery shows the row and all its index entries, or none of them.
10. Docs: `architecture/d1-record-layer.md` (the table→KV mapping diagram, a row's bytes
    end to end), glossary, README status, changelog, tag `d1-record`.

## Acceptance gates

- **Order:** for generated values a, b of every type, `compare(encode(a), encode(b))` equals
  the value order (jqwik, including NULL, ±0.0, NaN, empty and 0x00-containing text).
- **Atomic rows:** no crash point leaves an index entry without its row or a row missing an index
  entry. `verify()` is clean after every recovery.
- **Index states:** kill at every file operation of a backfill; after recovery the index is
  either absent or complete, and never used while incomplete.
- **Constraints:** a duplicate primary key or unique-index value is rejected, and nothing is
  written.
- **Formats:** golden files for encoded keys and rows; bit-flip tests are detected or provably
  harmless.
- The module's runtime dependency graph is empty; `shale-core` is unchanged.

## References

FoundationDB "Tuple layer" specification; CockroachDB "Structured data encoding in CockroachDB
SQL" (tech note) and Taft et al. (SIGMOD 2020); MyRocks memcomparable format (Matsunobu et al.,
VLDB 2020); PostgreSQL system catalogs docs; Petrov, *Database Internals* ch. 1 and 3.
