# Changelog

One entry per milestone, newest first. Each is tagged on `main`; the as-built design is in
[`documentation/architecture/`](documentation/architecture/) and the decisions in
[`documentation/adr/`](documentation/adr/). What comes next is in the
[plan](documentation/roadmap/completion-plan.md).

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
