# M7 — Atomic batches, snapshots, bloom filters, per-operation statistics: implementation plan

**Status:** planned; starts after M6 is tagged. Two parts, each on its own branch with its own ADR:
**part 1** — batches, snapshots and per-operation statistics (the SPI change ShaleDB is built on);
**part 2** — bloom filters. Tag `m7-mvcc-bloom` after both.

**Depends on:** M6 (compaction learns the snapshot-aware drop rule), M5.5 (the visible sequence
watermark and the writer queue), M4 (`ReconcilingCursor` gains its fourth rule).

**Goal:** the three guarantees and one capability a database layer needs from its engine. A group
of writes commits all-or-nothing. A reader sees one consistent state for as long as it needs. A
lookup skips tables that cannot hold its key. And every read and write can report what it cost —
the hook D6's `EXPLAIN ANALYZE` is built on.

---

## Part 1 — batches, snapshots, per-operation statistics

### Design (decided — ADR-0016 records it; public API, `Reversible: no`)

1. **The SPI additions** — one options record instead of an overload per feature:
   ```java
   void write(WriteBatch batch, Durability durability);
   CompletableFuture<WriteResult> writeAsync(WriteBatch batch, Durability durability);
   Snapshot snapshot();                                   // AutoCloseable
   byte[] get(byte[] userKey, ReadOptions options);
   Cursor scan(byte[] fromInclusive, byte[] toExclusive, ReadOptions options);
   record ReadOptions(Snapshot snapshot, OperationStats stats) { }  // either may be null
   ```
   `put` and `delete` become one-entry batches internally.
2. **`writeAsync` ordering.** The call enqueues synchronously, so a batch's sequence numbers are
   fixed by call order. The future completes once the batch is durable under its `Durability` and
   visible. This is what lets ShaleDB (D4) commit transactions in its own commit order while still
   sharing a group-commit fsync. `WriteResult` carries the first sequence number, the WAL bytes,
   the group size and whether the group forced.
3. **WAL format v2.** The version lives in each segment's header (`wal/format.md` §1), so a v2
   segment's every logical record is a batch: base sequence (fixed64), count (fixed32), then typed
   entries (LevelDB's `WriteBatch` rep). v1 segments still replay as single mutations. The work:
   - `format.md` updated;
   - the v1 golden file still recovers, and a new v2 golden is added;
   - `Format-Change:` trailer.

   A torn batch is never partly replayed; the CRC covers the whole logical record.
4. **Visibility.** Each batch takes consecutive sequence numbers. The visible watermark advances
   past a batch only when all of it is in the memtable. A read without a snapshot reads at the
   current watermark, so nobody sees half a batch.
5. **Reads at a snapshot.** `get` seeks with the snapshot's sequence instead of `MAX_SEQUENCE`;
   `ReconcilingCursor` skips entries above it (its fourth rule).
6. **Retention.** The engine tracks live snapshots, and compaction keeps what the smallest one
   needs (LevelDB's `smallest_snapshot`). An open snapshot pins old versions; that pinning is
   reported as `snapshot.oldest.age`.
7. **`OperationStats`.** A caller-owned, `@NotThreadSafe` accumulator — RocksDB's `PerfContext`,
   but passed explicitly rather than thread-local. It counts:
   - memtables probed and SSTables probed;
   - filter skips (from part 2);
   - blocks read and bytes read;
   - entries visited, shadowed versions skipped and tombstones skipped.

   A cursor keeps reporting into it as it advances. With `null`, the path pays one branch per
   event, and a JMH benchmark proves that before the design is accepted.

### Gates

- A batch of N entries is wholly visible or wholly absent: at every crash offset of its WAL
  record, and to concurrent readers during its insertion (a controlled interleaving test).
- `writeAsync` calls made in order A, B get sequences A < B, even when they share one group.
- A snapshot taken before N writes sees exactly the pre-N state after those writes, after a flush
  and after a compaction. Closing it lets compaction reclaim the shadowed versions.
- `OperationStats` for scripted reads equals hand-computed counts. Example: a key living in L2
  under two memtables and three L0 tables reports 2 memtables probed, then the tables probed and
  blocks read along the documented lookup order.
- Model harness: batches and snapshot reads join the operation mix.

## Part 2 — bloom filters

### Design (decided — ADR-0017 records it; SSTable format v2)

1. **Granularity:** one whole-table filter per SSTable (RocksDB's "full filter"), stored as a
   filter block named in the metaindex, which v1 left empty for exactly this.
2. **Hash and probes:** a hand-written 32-bit hash (LevelDB's `Hash`) with double hashing
   (Kirsch–Mitzenmacher) to derive k probes, where k = bits-per-key × ln 2, stored in the block.
3. **Sizing:** bits per key is a `ShaleOptions` field (default 10, about 1% false positives);
   0 disables filters.
4. **Compatibility:** v1 tables have no filter and are always probed. The work: `format.md`
   updated, the v1 golden still readable, a v2 golden, `Format-Change:`.

### Gates

- **Never a false negative:** property test, including exhaustive small sets.
- Measured false-positive rate within 15% of theory at 5, 10 and 15 bits per key.
- A missing-key lookup (`readmissing`) improves measurably with filters on — the operation behind
  every SQL `INSERT`'s duplicate-key check.
- `OperationStats` counts each skip.

## Task order (per part: ADR → format → code → gates → docs)

1. **Part 1:**
   - ADR; WAL v2 `format.md` and goldens;
   - `WriteBatch` and its codec;
   - `writeAsync` on the M5.5 queue; visible-watermark reads; the snapshot registry;
   - the cursor's fourth rule; compaction's `smallestSnapshot`;
   - `OperationStats` threaded through `get`, the SSTable iterator and the cursor; model harness.
2. **Part 2:** ADR; SSTable v2 `format.md` and goldens; `dev.shale.filter.BloomFilter` and its
   builder; writer and reader integration; the skip in the `get` path; FPR measurement.
3. **Docs:** `package-info`s, glossary rows, `architecture/m7-batches-snapshots-bloom.md`,
   README status, changelog; tag `m7-mvcc-bloom`.

## References

LevelDB `db/write_batch.cc`, `db/snapshot.h`, `util/bloom.cc`; RocksDB wiki "PerfContext and
IOStatsContext" and "RocksDB Bloom Filter"; Bloom (1970); Kirsch & Mitzenmacher, "Less Hashing,
Same Performance" (2006); Petrov, *Database Internals* ch. 5 and 7.
