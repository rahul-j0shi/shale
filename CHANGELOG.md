# Changelog

One entry per milestone, newest first. Each is tagged on `main`; the as-built design is in
[`documentation/architecture/`](documentation/architecture/) and the decisions in
[`documentation/adr/`](documentation/adr/). What comes next is in the
[plan](documentation/roadmap/completion-plan.md).

## M5 — Manifest and recovery · `m5-manifest`

The set of live files now comes from a durable manifest, not from listing the directory (ADR-0012).
The [explainer](documentation/architecture/m5-manifest-and-recovery.md) shows the open and flush
sequences with every sync marked.
- **Manifest v2** (new file, born on log framing v2): LevelDB's version-edit tags, with each
  table's sequence range; `CURRENT` names the live manifest and is switched through a synced
  temp file, a rename and a directory sync. Rolled over past `manifestRolloverBytes`.
- **`Version` and `VersionSet`:** reads retain a Version; a table removed by an edit is deleted by
  the release that drops its last reference — never under a reader, never before the edit is
  durable.
- **Recovery through the manifest:** only the segments it still needs are replayed; a new
  full-state manifest is the commit point; orphans are deleted after it; a refused open changes
  nothing and releases everything (SH-02). A pre-M5 directory is migrated on its first open.
- **Power-loss durability:** a new segment's header and directory entry, and a flushed table's
  rename, are synced before anything depends on them (SH-04).
- **Lifecycle:** the failed state after a flush error (SH-01); idempotent `close()` and rejection
  after it; `LOCK`; file numbers past 999,999 (SH-09); a cursor that cannot position gives its
  references back (SH-10); `CorruptionException` names the file.
- **API:** `ShaleOptions`; the public `Env` seam (`PosixEnv`); `FaultInjectionEnv` in `shale-core`'s
  test fixtures.
- **Tests:** the crash matrix (six workloads × every operation × two loss models), the
  power-loss test, a metamorphic test over eight configurations, and the manifest golden written
  by an independent implementation. 371 tests (355 fast, 16 in the crash tier), up from 279.
  Format change: manifest v2 (new).

## M4.5 — Integrity boundaries · `m4.5-integrity`

Recovery, and every decoder, now decides only on bytes it has verified (ADR-0024). The
[case study](documentation/architecture/m4-5-integrity-boundaries.md) tells why.
- **WAL format v2** (log framing v2): a CRC32C over each fragment header, checked before its
  length or type is used. Two one-bit header faults that made M4 open empty and delete the WAL
  (SH-08, SH-18) now fail loudly. v1 segments are still read: `ZERO` only as an all-zero tail, and
  a short v1 payload refused as *unverifiable* with an explicit operator procedure.
- **The recovery boundary:** only the newest WAL segment may end torn; a segment is synced when it
  closes; a refused `open` changes no file.
- **The decoder contract:** varints bounded at 64 bits (SH-11) and at 32 for `varint32` fields;
  WAL record lengths checked in context (SH-13); SSTable restart arrays (SH-14), file layout
  (SH-15) and block handles (SH-16) validated, all through `CorruptionException`.
- **The shared log framing** moves to `dev.shale.internal.log`, ready for M5's manifest.
  `RecordType` moves with it.
- **Tests the WAL never had:** every bit of every header, every truncation offset, the review's
  header matrix, malformed structures with valid checksums; v1 and v2 goldens.
- **The bootstrap** fetches JDK 25 on Linux and macOS, x64 and ARM; CI runs it on three platforms.
- 279 tests (271 fast, 8 in the crash tier), up from 161. Format change: WAL v2.

## M4 — Multi-SSTable reads and the merge iterator · `m4-merge`

- One read seam, `InternalIterator` (ADR-0011), implemented by the skiplist, the tree memtable and
  a two-level SSTable iterator that opens data blocks lazily.
- `MergingIterator`: a heap-based multi-way merge that merges without interpreting, so compaction
  can reuse it. `ReconcilingCursor`: newest version per user key wins, tombstones hide.
- `scan` streams: the lower bound is a `seek` into every source, and cost scales with the result,
  not the database. The cursor retains every SSTable it can read and releases them on `close()`.
- 159 tests; no format change.

## M3 — SSTable write and flush · `m3-sstable`

- A LevelDB-style block table (ADR-0010): prefix-compressed data blocks with restart points, an
  index block, an empty metaindex reserved for filters, a versioned footer, and CRC32C per block.
- Flush: a full memtable is written to a temp file, fsynced and atomically renamed *before* its WAL
  segment is deleted.
- A golden SSTable fixture, a round-trip property and a bit-flip test at every byte offset. 98 tests.

## M2 — Skiplist memtable and immutable handoff · `m2-skiplist`

- A hand-written skiplist (ADR-0009): one writer, lock-free readers via safe publication.
- Memory accounting and a memtable switch at the write-buffer size (default 4 MiB).
- A differential property test against a balanced-tree oracle; a stress test of four readers
  against a live writer; the engine model test through switches and restarts.

## M1 — Write-ahead log · `m1-wal`

- A LevelDB block-structured WAL (ADR-0007): 32 KiB blocks, CRC32C per fragment, a versioned header.
- Durability chosen per write — `NONE`, `SYNC`, `GROUP` (ADR-0008) — with the fsync marked in code.
- Recovery by replay; a torn tail is truncated under an explicit policy, interior corruption throws.
- A crash test truncating the WAL at every byte offset; a golden WAL fixture. 61 tests.

## M0 — Skeleton and interfaces · `m0-skeleton`

- The `StorageBackend` SPI (ADR-0006): byte keys and values, explicit durability, a named comparator.
- Internal-key encoding (ADR-0004), little-endian fixed-width integers (ADR-0005), concurrency
  annotations, and the exception hierarchy.
- The differential model harness: random operations against a `TreeMap` oracle, restarts included.
