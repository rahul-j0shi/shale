# M7 — Atomic batches, snapshots, filters and cache: implementation plan

**Status:** planned 2026-09-26; starts after M6 is tagged. Three slices, each with its own ADR,
branch and tag, in this order: **M7a** batches + snapshots, **M7b** bloom filters, **M7c** block
and table cache. M7a comes first because ShaleDB (D1) cannot start without it; M7b and M7c are
read-performance work that the fast-track (completion plan §7) may postpone.

**Depends on:** M6 (compaction must learn the snapshot-aware drop rule), M5.5 (the visible
sequence watermark), M4 (`ReconcilingCursor` gains its fourth rule).

---

## M7a — `WriteBatch` and `Snapshot`

**Goal:** a group of puts and deletes commits all-or-nothing, and a reader can see one consistent
state for as long as it needs. These are the two guarantees a relational layer is built on: a row
and its index entries commit together, and a query reads one state.

### Decisions required in the ADR (public API, `Reversible: no`)

1. **SPI.** Add `void write(WriteBatch batch, Durability durability)`, `Snapshot snapshot()`, and
   `get(byte[], Snapshot)` / `scan(byte[], byte[], Snapshot)` overloads. `put` and `delete`
   become one-entry batches internally. `Snapshot` is `AutoCloseable`; closing it unregisters it.
2. **WAL record.** One batch = one WAL payload: base sequence (fixed64), count (fixed32), then
   typed entries (LevelDB `WriteBatch` rep). This is **WAL payload format v2**: `format.md`
   update, the v1 golden still reads, a new v2 golden, `Format-Change:`. A torn batch is never
   partly replayed; the WAL CRC already covers the whole logical record across fragments.
3. **Visibility.** Each batch takes consecutive sequence numbers. The visible watermark (M5.5)
   advances past a batch only after all of it is in the memtable. A read with no snapshot reads
   at the current visible watermark, so no reader can see half a batch. This closes a gap: today
   `get` would see any memtable entry as soon as it is inserted.
4. **Reads at a snapshot.** `get` seeks with the snapshot's sequence instead of `MAX_SEQUENCE`;
   `ReconcilingCursor` skips entries above the snapshot sequence (its fourth rule).
5. **Retention.** The engine tracks live snapshots, and compaction receives the smallest one
   (LevelDB's `smallest_snapshot` rule). Per-snapshot version stripes (RocksDB) are a stretch.
6. **Limits.** A maximum batch size (default 64 MiB) is rejected with `IllegalArgumentException`;
   a snapshot held open pins old versions, reported as `snapshot.oldest.age`.

### Gates

- A batch of N entries is either wholly visible or wholly absent: at every crash offset of its
  WAL record, and to concurrent readers during its insertion (a controlled interleaving test).
- A snapshot taken before N writes sees exactly the pre-N state after those writes, after a
  flush and after a compaction. Closing it lets compaction reclaim the shadowed versions.
- Model harness: batches and snapshot reads join the operation mix; the oracle keeps a
  copy-on-write history of states.
- The v1 WAL golden still recovers; the v2 golden and bit-flip tests pass.

---

## M7b — Bloom filters

**Goal:** a point lookup skips any SSTable that certainly lacks the key.

### Decisions required in the ADR (SSTable format v2)

1. **Granularity.** Recommended: one whole-table filter per SSTable (RocksDB "full filter"),
   written as a filter block named in the metaindex. It is simpler than LevelDB's per-2 KiB
   filters and suits point lookups.
2. **Hash and probes.** A hand-written 32-bit hash (LevelDB's `Hash`, Murmur-like) and double
   hashing (Kirsch–Mitzenmacher) to derive k probes; k = bits-per-key × ln 2, stored in the block.
3. **Sizing.** Bits per key is an option (default 10, about 1% false positives); 0 disables
   filters. Monkey-style allocation by level is a measured stretch for M8.
4. **Compatibility.** SSTable v1 files have no filter block and are always probed. Adding the
   filter handle is a format change: `format.md`, v1 golden still readable, v2 golden,
   `Format-Change:`.

### Gates

- **Never a false negative:** property test, including exhaustive small sets.
- Measured false-positive rate within 15% of theory at 5, 10 and 15 bits per key.
- `readmissing` improves measurably with filters on; the numbers go in the release note.
- Metric `filter.useful` counts tables skipped; `filter.fpr` is derived.

---

## M7c — Block cache and table cache

**Goal:** hot data blocks and open table handles stay in memory within a fixed budget.

### Decisions required in the ADR

1. **Block cache:** hand-written (Caffeine is banned), sharded LRU keyed by (file number, block
   offset), with a byte budget. File numbers are never reused, so a deleted table's blocks can
   never be served for another file. CLOCK versus LRU is a measured stretch.
2. **Table cache:** bounds the number of open `SSTableReader`s. Eviction releases the cache's
   reference, and the M5 reference counting keeps a table open while a reader holds it.
3. **What is cached:** decoded data blocks; index and filter blocks stay pinned with the table.

### Gates

- Memory stays within budget under a random read load; hit and miss metrics are exact in a
  scripted scenario.
- A cached block is never served after its table is obsolete and released.
- `readrandom` on a hot set improves measurably; the numbers go in the release note.

---

## Task order (per slice: ADR → format → code → gates → docs → tag)

1. **M7a:** ADR; WAL v2 `format.md` + goldens; `WriteBatch` type and codec; visible-watermark
   reads; snapshot registry; cursor fourth rule; compaction `smallestSnapshot`; model harness.
2. **M7b:** ADR; SSTable v2 `format.md` + goldens; `dev.shale.filter.BloomFilter` and builder;
   writer/reader integration; get-path skip; FPR measurement.
3. **M7c:** ADR; `dev.shale.cache` block cache and table cache; integration; metrics.
4. For each slice: `package-info`, glossary rows, an `architecture/m7x-*.md` page, README status,
   release note, tag `m7a-mvcc` / `m7b-bloom` / `m7c-cache`.

## References

LevelDB `db/write_batch.cc`, `db/snapshot.h`, `util/bloom.cc`, `table/filter_block.cc`,
`util/cache.cc`; RocksDB wiki "RocksDB Bloom Filter", "Block Cache"; Bloom (1970); Kirsch &
Mitzenmacher, "Less Hashing, Same Performance" (2006); Dayan et al., "Monkey" (SIGMOD 2017);
Petrov, *Database Internals* ch. 5 and 7.
