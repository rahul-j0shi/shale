# M7 — Atomic batches, snapshots, per-operation statistics, bloom filters: implementation plan

**Status:** planned; starts after M6 is tagged. **Depends on:**
- M5.5: the write queue and writer thread, `visibleSequence`, `WriteResult`;
- M6: `CompactionJob`'s `smallestSnapshot` parameter, the lookup order;
- M4: `ReconcilingCursor`.

**ADRs:** 0016 (part 1), 0017 (part 2). **Estimate:** 3–4 focused weeks, in 8 steps. **Tag:**
`m7-mvcc-bloom`.

**Goal.** The three guarantees and one capability ShaleDB needs from its engine:
- a group of writes commits all-or-nothing;
- a reader sees one consistent state for as long as it needs;
- a point lookup skips tables that cannot hold its key;
- every read and write can report what it cost — the hook D6's `EXPLAIN ANALYZE` is built on.

---

## 1. Where the code will be at the start (after M6)

| Fact | Where |
|---|---|
| A write is one mutation; `WriteQueue` groups requests and returns a package-private `WriteResult` | M5.5 |
| The WAL payload is one mutation; segment header `FORMAT_VERSION` 1 | `wal/format.md`, `WalRecordCodec` |
| `get` seeks `(userKey, MAX_SEQUENCE)`; `ReconcilingCursor` seeks `(from, MAX_SEQUENCE)` and takes the newest version of every key | `Shale.java:291`, `ReconcilingCursor.java:90` |
| Readers see every memtable entry as soon as the writer thread inserts it; `visibleSequence` is maintained but unused | M5.5 §2.2 |
| `CompactionJob` drops by `smallestSnapshot`, which is `visibleSequence` | M6 §2.5 |
| The SSTable metaindex block is empty; footer `FORMAT_VERSION` 1 | `sstable/format.md` |
| `StorageBackend` has `put`, `delete`, `get`, `scan`; the test `ReferenceBackend` implements it | ADR-0006 |

## 2. Part 1 — design (decided — ADR-0016; public API, `Reversible: no`)

### 2.1 The API

```java
// dev.shale — all new public types
public final class WriteBatch {                 // @NotThreadSafe; owned by its builder thread
  public WriteBatch put(byte[] userKey, byte[] value);
  public WriteBatch delete(byte[] userKey);
  public int count();
  public long approximateSizeBytes();
  public void clear();
}
public record WriteResult(long firstSequence, long lastSequence, long walBytes,
                          int groupSize, boolean synced) { }
public final class Snapshot implements AutoCloseable {   // @ThreadSafe
  public long sequence();
  public void close();                                   // idempotent
}
public record ReadOptions(Snapshot snapshot, OperationStats stats) {   // either may be null
  public static final ReadOptions DEFAULT = new ReadOptions(null, null);
  public static ReadOptions at(Snapshot snapshot);
  public ReadOptions withStats(OperationStats stats);
}
public final class OperationStats { /* §2.5 */ }         // @NotThreadSafe; caller-owned

// StorageBackend gains (ADR-0006's "additive evolution"):
void write(WriteBatch batch, Durability durability);
CompletableFuture<WriteResult> writeAsync(WriteBatch batch, Durability durability);
Snapshot snapshot();
byte[] get(byte[] userKey, ReadOptions options);
Cursor scan(byte[] fromInclusive, byte[] toExclusive, ReadOptions options);
```

- **`WriteBatch` ownership.** `write` and `writeAsync` encode the batch *during the call*, so the
  caller may `clear()` and reuse it as soon as the call returns. An empty batch is legal: it
  returns without touching the WAL.
- **Limits.** A batch over 32 MiB is `IllegalArgumentException`. With the queue bound at 64 MiB
  (M5.5), a lone maximum batch is always admitted. The queue admits a request while queued bytes
  are *at or below* the bound, even if it pushes over.
- **Key and value limits.** A key over **16 KiB** or a value over **16 MiB** is
  `IllegalArgumentException`, checked in `WriteBatch.put`/`delete` and so by every write path.
  - **Why a stated limit.** Until M7 the only limit is an accident of the encodings (a varint32
    length), and a user finds it by failing. A stated limit is part of the API, and the
    embedding guide documents it.
  - **Why these numbers.** A key is copied into index blocks, restart points and the memtable's
    nodes, so a large key costs many times its size; 16 KiB is far above any sensible key.
    16 MiB is half the batch limit, so a maximum value always fits in a batch.
  - **D1** maps the error to SQLSTATE `54000` (`program_limit_exceeded`) for a row or index key
    over the limit.
- **Old methods.** `put`, `delete`, `get(byte[])` and `scan(from, to)` stay. They become a
  one-entry batch and `ReadOptions.DEFAULT` respectively.
- **The test backend.** `ReferenceBackend` implements the new methods: batches applied atomically
  to its `TreeMap`; snapshots as copies.

### 2.2 `writeAsync` — ordering and completion

- **Order is fixed at the call.** `writeAsync` encodes the batch and calls `WriteQueue.submit`
  before returning. So two calls made in order A, B from one thread, or under one lock, get
  sequences A < B, even if they end up in one group.
- **It never does I/O in the caller's thread.** M5.5's queue guarantees that. It blocks only on
  the queue's byte bound.
- **The future completes** after the batch is durable under its `Durability` *and* visible (group
  steps 6–8 of M5.5 §2.1). It completes exceptionally with `EngineStateException` on failure.
- **`write`** is `writeAsync(...).join()`, unwrapping the exception.

### 2.3 WAL format version 2

- **Where the version lives.** The version is in each segment's header (`wal/format.md` §1). A
  segment written by M7 has `FORMAT_VERSION` 2, and **every logical record in it is one batch**:

  | Field | Encoding |
  |---|---|
  | base sequence | fixed64LE |
  | entry count | fixed32LE |
  | each entry | type (1 byte: 1 = put, 0 = delete — `ValueType`'s codes), varint32 key length, key, and for a put: varint32 value length, value |

  Entry *i* has sequence base + *i*.
- **Old segments still work.** Replay dispatches on the segment's header version: v1 segments
  replay as one mutation per record, as today.
- **Torn and corrupt records.** A batch is one logical record, and the CRC covers every fragment,
  so a torn batch is dropped whole (`TRUNCATE_TAIL`). A count or length that overruns the record
  is `CorruptionException`.

### 2.4 Visibility and snapshots

- **Default reads** read at `visibleSequence`: `get` seeks `(userKey, visibleSequence)`, and the
  cursor ignores entries above it. A reader can therefore never see part of a group, or part of a
  batch.
- **`snapshot()`**:
  - registers `visibleSequence` in a `SnapshotRegistry` — a `TreeMap<Long, Integer>` of sequence
    → count, under its own lock;
  - `close()` unregisters it.
  - A snapshot pins no files. Retention is compaction's job: `smallestSnapshot` = the registry's
    smallest key, or `visibleSequence` if the registry is empty.
- **Reads at a snapshot:**
  - `get(key, ReadOptions.at(s))` seeks `(key, s.sequence())`;
  - `scan(..., ReadOptions.at(s))` seeks `(from, s.sequence())`. `ReconcilingCursor` gains its
    **fourth rule**: skip every entry whose sequence > the read sequence.
- **Leak backstop:** a `Cleaner` logs a `WARN` with the creation stack trace for a snapshot that
  was never closed. Correctness never depends on it (N6).
- **Metrics:** `snapshot.live.count` (gauge); `snapshot.oldest.age` (the oldest live snapshot's
  age in sequence numbers).

### 2.5 `OperationStats`

- **What it is:** a mutable counter set. The caller creates one, passes it in `ReadOptions`, and
  reads it afterwards. It is RocksDB's `PerfContext`, made explicit instead of thread-local, so
  D6 can give every SQL operator its own.

| Counter | Incremented when |
|---|---|
| `memtablesProbed` | `get` probes a memtable |
| `tablesProbed` | `get` calls `ceiling` on an SSTable |
| `filterSkips` | a bloom filter rules a table out (part 2) |
| `blocksRead`, `bytesRead` | an SSTable data block is read and verified |
| `entriesVisited` | a cursor or lookup examines an internal entry |
| `versionsSkipped` | an older version of a key is passed over |
| `tombstonesSkipped` | a tombstone hides a key during a scan, or ends a lookup |
| `newerThanSnapshotSkipped` | the fourth rule skips an entry |

- **Plumbing:** `SSTableReader.ceiling(key, stats)` and `SSTableReader.iterator(stats)`;
  `ReconcilingCursor` holds the stats for its lifetime.
- **Cost when unused.** With `null` stats every site pays one null check. Step 5's JMH benchmark
  must show `get` with null stats within noise of M6's `get` before part 1 merges.

## 3. Part 2 — design (decided — ADR-0017; SSTable format version 2)

- **Keyed by the user key.** The filter is built from each entry's *user* key, not its internal
  key, because a lookup knows only the user key (LevelDB's `InternalFilterPolicy` strips the
  sequence the same way).
- **One filter per table.** It is stored as a filter block: the bit array, then one byte holding
  *k*. It is named in the metaindex as `filter.shale.bloom` → its `BlockHandle`.
- **The hash.** LevelDB's `Hash(data, n, seed = 0xbc9f1d34)`, hand-written; the probes use double
  hashing: `h += delta; delta = (h >>> 17) | (h << 15)` (Kirsch–Mitzenmacher, as LevelDB does).
- **Sizing:**
  - bits = max(64, keys × `bitsPerKey`), rounded up to a byte;
  - *k* = round(`bitsPerKey` × 0.69), clamped to [1, 30];
  - `ShaleOptions.bloomBitsPerKey`, default 10 (about 1% false positives); 0 writes no filter.
- **Lookups.** `get` asks `table.mayContain(userKey)` before `ceiling`, and on "no" counts a
  `filterSkip` and moves on. Scans do not use filters.
- **Compatibility.** Footer `FORMAT_VERSION` 2. The reader accepts 1 (no filter: always probe) and
  2. Flushes and compactions always write 2.

## 4. New and changed types

| Type | Package | Step |
|---|---|---|
| `WriteBatch`, `WriteBatchCodec` | `dev.shale`, `dev.shale.wal` | 2 |
| `WalFormat` (version 2), `WalReader` (dispatch on version) | `dev.shale.wal` | 2 |
| `WriteResult` (now public), `StorageBackend` (5 methods), `Shale`, `ReferenceBackend` (test) | `dev.shale` | 3 |
| `Snapshot`, `SnapshotRegistry` (package-private), `ReadOptions` | `dev.shale` | 4 |
| `ReconcilingCursor` (fourth rule, read sequence) | `dev.shale.iterator` | 4 |
| `OperationStats`; stats-taking `SSTableReader.ceiling`/`iterator` | `dev.shale`, `dev.shale.sstable` | 5 |
| `Hash`, `BloomFilter`, `BloomFilterBuilder` | `dev.shale.filter` | 7 |
| `SSTableWriter` (collects user-key hashes, writes the filter), `SSTableReader` (loads it, `mayContain`), `SSTableFormat` (version 2) | `dev.shale.sstable` | 7 |
| `ShaleOptions` (adds `bloomBitsPerKey`) | `dev.shale` | 7 |

## 5. Steps

### Step 1 — ADR-0016 (`adr/0016-batches-snapshots`), ~1 day
`docs(api)`: records §2. Alternatives:
- overloads per feature instead of `ReadOptions` (rejected: combinatorial growth);
- thread-local stats as in RocksDB (rejected: D6 needs per-operator stats, and threads are shared);
- snapshots pinning Versions (rejected: pins files; retention by sequence is enough);
- a per-record version byte in the WAL (rejected: the segment header already carries a version).

**Done when:** merged.

### Step 2 — `WriteBatch` and WAL v2 (`m07/write-batch`), ~4 days
1. `docs(wal)`: `wal/format.md` — v2 record layout, a worked hex example of a two-entry batch, the
   version history.
2. `feat(wal)`: `WriteBatch`, `WriteBatchCodec`; the writer thread writes v2 segments with one
   record per batch; replay dispatches on version.
3. `test(wal)`:
   - the golden `golden/wal/v2/two-entry-batch.wal` and its `.json`;
   - `GoldenWalTest` still reads the v1 golden;
   - a round-trip property over random batches;
   - bit-flip at every offset of the v2 golden;
   - a count or length overrun is corruption.
4. `test(recovery)`: `ShaleCrashTest` — a new test truncates a v2 segment holding three batches at
   every byte offset. Recovery holds whole batches, never part of one.

Commit the format with `Format-Change: wal v2 — one batch per record` and `Reversible: no`.
**Done when:** these pass; existing tests green.

### Step 3 — the public write API (`m07/write-api`), ~2 days
1. `feat(api)`: `write`, `writeAsync`, public `WriteResult`, `StorageBackend` additions, the
   `ReferenceBackend` implementation, the 32 MiB limit, the key and value limits.
2. `test(api)`, `ShaleWriteBatchTest`:
   - a batch's keys all appear together;
   - `writeAsync` A then B from one thread gives A.lastSequence < B.firstSequence;
   - the caller may reuse a batch after the call;
   - an empty batch is a no-op;
   - an oversized batch → `IllegalArgumentException`;
   - a 16 KiB key and a 16 MiB value are accepted; one byte more of either →
     `IllegalArgumentException`, through `put` and through `WriteBatch`;
   - `writeAsync` returns before any sync (with syncs held).

**Done when:** green.

### Step 4 — visibility and snapshots (`m07/snapshots`), ~4 days
1. `feat(api)`: default reads at `visibleSequence`; `Snapshot`, `SnapshotRegistry`,
   `ReadOptions`; the cursor's fourth rule; `CompactionJob` receives the registry's smallest
   snapshot; metrics; the leak `Cleaner`.
2. `test(api)`, `ShaleSnapshotTest`:
   - a snapshot taken before N writes reads exactly the pre-N state — after the writes, after a
     flush, and after a compaction;
   - closing it lets the next compaction drop the shadowed versions (`bytes.compaction.written`
     shrinks accordingly);
   - `close()` twice is harmless.
3. `test(api)`, `ShaleBatchVisibilityTest`:
   - with syncs held on a group of two batches, readers see neither;
   - after release, readers see both, and never one without the other (a reader thread samples
     both keys 10,000 times; each sample shows both or neither).

**Done when:** green.

### Step 5 — `OperationStats` (`m07/operation-stats`), ~3 days
1. `feat(api)`: `OperationStats`; threaded through `get`, `SSTableReader`, `ReconcilingCursor`.
2. `test(bench)`: `GetOverheadBenchmark` — `get` with null stats vs M6's tag, same data. Its
   numbers go in the merge commit body.
3. `test(api)`, `OperationStatsTest`, with exact expected counts:
   - a key in L2 under two memtables and three overlapping L0 tables;
   - a scan over 100 keys, 30 of them deleted: `tombstonesSkipped` = 30;
   - a scan at a snapshot with 10 newer versions present: `newerThanSnapshotSkipped` = 10.

**Done when:** exact counts match, and the overhead is within noise.

### Step 6 — ADR-0017 (`adr/0017-bloom-filter`), ~½ day
`docs(filter)`: records §3. Alternatives:
- LevelDB's per-2 KiB filters (rejected: per-table is enough and simpler);
- a filter keyed by internal key (rejected: useless for lookups).

**Done when:** merged.

### Step 7 — bloom filters (`m07/bloom`), ~4 days
1. `docs(sstable)`: `sstable/format.md` v2 — the filter block, the metaindex entry, a worked
   example, the version history.
2. `feat(filter)`: `Hash`, `BloomFilter`, `BloomFilterBuilder`.
3. `feat(sstable)`: the writer builds the filter; the reader loads it at open; `get` skips.
4. **Tests:**
   - `HashTest`: LevelDB's published test vectors;
   - `BloomFilterPropertyTest`: no false negatives, including exhaustive sets up to 100 keys;
   - `BloomFilterRateTest`: 10,000 keys, 100,000 absent probes (seeded); the measured
     false-positive rate is within 15% of (1 − e^(−kn/m))^k at 5, 10 and 15 bits per key;
   - `GoldenSSTableTest`: the v1 golden still reads; a new v2 golden with a filter; bit-flip on
     the v2 golden;
   - `OperationStatsTest`: a missing key over 5 tables reports 5 `filterSkips` and 0
     `blocksRead`.

Commit with `Format-Change: sstable v2 — filter block` and `Reversible: no`. **Done when:** green.

### Step 8 — harnesses, documentation, tag (`m07/docs`), ~3 days
1. `test(memtable)`: `EngineModelTest` adds batches and snapshot reads. The oracle copies its
   `TreeMap` when a snapshot is taken.
2. `test(recovery)`: `ShaleCrashMatrixTest` adds a batch workload. After recovery, every batch is
   whole or absent.
3. Docs:
   - `package-info` for `filter`; N9 citations (LevelDB `write_batch.cc`, `snapshot.h`,
     `bloom.cc`, `hash.cc`; RocksDB `PerfContext`; Bloom 1970; Kirsch–Mitzenmacher 2006);
   - `architecture/m7-batches-snapshots-bloom.md` (the read-sequence rule, visibility across a
     group, the filter on the lookup path);
   - glossary rows: write batch, snapshot, read sequence, operation statistics, false-positive
     rate;
   - README status; changelog (with the measured false-positive rates and the `readmissing`
     gain); the completion plan's status table;
   - `guides/embedding-shale.md`: `WriteBatch`, `writeAsync`, snapshots and `ReadOptions` in the
     API section; the key and value limits; the lifted rows of the limitations table. The FAQ's
     transactions answer.
4. **Reconciliation pass for M8.** Tag `m7-mvcc-bloom`.

## 6. Milestone acceptance gates

- **Atomic batches:** whole or absent at every crash offset and to every reader.
- **Snapshots:** exact pre-state across flush and compaction; retention released on close.
- **Order:** `writeAsync` order = sequence order.
- **Statistics:** exact counts in scripted scenarios; negligible cost when unused.
- **Filters:** no false negatives; false-positive rate within 15% of theory; old tables still
  read.
- Every existing test stays green.

## 7. Not in M7

A block or table cache (out of scope, ADR-0013); per-level filter sizing (Monkey); prefix
filters; filters on scans; transactions (D4 builds them on this milestone).

## References

LevelDB `db/write_batch.cc`, `db/snapshot.h`, `util/bloom.cc`, `util/hash.cc`,
`db/dbformat.h` (`InternalFilterPolicy`); RocksDB wiki "PerfContext and IOStatsContext" and
"RocksDB Bloom Filter"; Bloom, "Space/Time Trade-offs in Hash Coding" (CACM 1970); Kirsch &
Mitzenmacher, "Less Hashing, Same Performance" (2006); Petrov, *Database Internals* ch. 5 and 7.
