# 0010. SSTable on-disk format: a LevelDB block table, uncompressed

- **Status:** Accepted
- **Date:** 2026-08-03
- **Milestone:** M3
- **Reversible:** **no** — this is an on-disk format (on-disk-formats.md §3). Files written by this
  version live on disk; changing the layout requires a `FORMAT_VERSION` bump, a golden file written
  by the old version, and the full §3 procedure. The footer is deliberately shaped to admit the M7
  filter block without a layout change (see Consequences).

## Context

M3 gives the engine its first on-disk table. An immutable memtable (M2) is serialised to an
**SSTable** — an immutable, sorted, on-disk key/value file — and afterwards its write-ahead log
segment can be deleted. Reads then consult the memtable set *and* the SSTables. This is the format
that every later milestone builds on: compaction rewrites SSTables (M6), bloom filters attach to them
(M7), the manifest tracks them (M5), and the multi-way merge reads across them (M4). Getting the byte
layout right now is worth the deliberation on-disk-formats.md §5 asks for, because after M4 it is
referenced everywhere.

The constraints are fixed by the conventions: little-endian throughout (ADR-0005), a magic number and
an explicit `FORMAT_VERSION` (on-disk-formats.md §2), a CRC32C over every block verified before its
length is trusted (§2), lengths bounds-checked before use (§2), and — crucially — **no third-party
format machinery and no compression codec before the format that uses it is hand-written** (N1). The
keys stored are the M0 encoded internal keys (ADR-0004), so an SSTable naturally holds MVCC versions
and tombstones, ordered by `InternalKeyComparator` (user key ascending, sequence descending).

What this ADR decides: the table's block structure, how keys are compressed within a block, how the
index locates a block, the footer layout, how tables are named and discovered before the manifest
exists, and how a flush is ordered against WAL-segment deletion.

## Options considered

### The table structure

**A1 — A flat sorted array of length-prefixed key/value pairs.** Simplest possible: write every
`(internalKeyLen, internalKey, valueLen, value)` in order, binary-search on read. It works, but it
throws away everything the milestone is meant to teach: no block granularity (so no per-block CRC, no
future block cache, no place to hang a bloom filter), no prefix compression, no restart points. A dead
end that M4–M7 would have to tear up.

**A2 — The LevelDB block table.** A sequence of **data blocks**, then a **metaindex block**, then an
**index block**, then a fixed **footer**. Inside a data block, keys are **prefix-compressed** against
the previous key with periodic **restart points** (every 16 keys) that store a full key and anchor a
binary search; a trailing restart-offset array plus count closes the block. Each block carries a
1-byte type + a CRC32C trailer. The index block holds one entry per data block — a separator key ≥ the
block's last key → a `BlockHandle{offset, size}`. This is the canonical, well-documented design
(LevelDB `table/`), the one Petrov ch. 3 and every reference engine use, and the substrate M4–M7 need.

**A3 — A copy-on-write B+tree file (LMDB-style).** The read-optimised in-place contrast. This is
deliberately the **M8 capstone** comparison backend, not the LSM path; building it here would be
building the wrong milestone.

### Key separators in the index

**B1 — Shortest separator (LevelDB `FindShortestSeparator`).** Store the shortest key that is ≥ the
block's last key and < the next block's first key, shrinking the index. An optimisation.

**B2 — The block's last key as its separator.** Correct and trivial: the index entry for block *i* is
its last key; a lookup finds the first index entry whose key ≥ target, which is the block that may
contain it. Slightly larger index, no behavioural difference.

### Compression

**C1 — Compress blocks now (Snappy/LZ4).** Banned by N1 until the format is hand-written and
understood, and there is nothing to measure yet.

**C2 — Store blocks uncompressed**, with a compression-type byte in the block trailer reserved as
`0x00 = none`, so a hand-written codec can be added later without a layout change.

### Versioning the footer

**D1 — Magic-only (LevelDB).** LevelDB distinguishes format by magic number alone; no version field.

**D2 — An explicit `FORMAT_VERSION` field in the footer**, read before anything else — required by
on-disk-formats.md §2.

### Naming, discovery, and flush ordering

**E1 — Discover SSTables via a manifest + `CURRENT` now.** That is milestone M5's job (atomic version
install, reference-counted lifecycle); pulling it forward doubles M3.

**E2 — Discover SSTables by directory scan for now.** On open, scan `NNNNNN.sst`; derive the maximum
sequence number from the tables and the replayed WAL. An explicit interim, replaced by the manifest at
M5.

**F1 — Flush on a background thread.** Matches production, but background threads, write stalls, and
their tests are sequenced at M6.

**F2 — Synchronous flush on the write path.** The obvious first implementation (CLAUDE.md §5): the
writer that fills a memtable flushes it. Blocks that one writer during the flush; readers stay
lock-free throughout.

## Decision

**A2 + B2 + C2 + D2 + E2 + F2.** The SSTable is a LevelDB block table with:

- **Data blocks** of prefix-compressed entries with restart points every 16 keys, each block followed
  by a `[type:1][crc32c:4]` trailer; `type = 0x00` (uncompressed) at M3.
- An **index block** whose entries are `lastKeyOfBlock → BlockHandle{offset, size}` (B2), and a
  **metaindex block** that is present but **empty** at M3 — the slot the M7 filter block handle will
  occupy.
- A fixed **footer** (52 bytes at the file's end): the metaindex and index `BlockHandle`s zero-padded
  to 40 bytes, then `FORMAT_VERSION` (`fixed32`, = 1), then `MAGIC` (`fixed64` = `0x5368616C65535354`,
  ASCII `"ShaleSST"`). Read from the end: magic, version, then the handles.
- **Naming:** `NNNNNN.sst`, six digits, allocated from **one monotonic file-number counter shared
  with WAL segments** (naming.md §7 — one counter, never per-kind).
- **Discovery (interim):** a directory scan on open (E2), with the manifest arriving at M5.
- **Flush (D3 ordering):** write the SSTable and `force()` it, **then** delete the covering WAL
  segment — the SSTable is durable before the log that also holds its data is dropped. Synchronous on
  the write path (F2).

The exact byte layout, a worked hex example, and the golden CRC live in
`shale-core/src/main/java/dev/shale/sstable/format.md`, written before the encoder (on-disk-formats.md
§1). SSTable file handles are reference-counted `retain()`/`release()` (N6); the full
delete-when-unreferenced lifecycle lands with the manifest at M5.

## Rationale

The block table (A2) is the whole point of the milestone: prefix compression and restart points are
the technique to learn, per-block CRC is the corruption boundary N4 wants, and the block/index/footer
split is what M4's merge, M5's manifest, M6's compaction, and M7's filters all attach to. A flat array
(A1) would be less code today and a rewrite tomorrow.

The two simplifications — last-key separators (B2) and synchronous flush (F2) — are the honest
application of "obvious implementation first, optimise only with a measurement" (CLAUDE.md §5).
Shortest separators shrink the index by a few bytes per block with no correctness change; a background
flush removes a single-writer stall. Neither is measured yet, both live behind stable seams, and both
have a clearly sequenced home (an optimisation pass; M6). Directory-scan discovery (E2) is the one
piece of scaffolding with a real successor already on the roadmap (M5's manifest); it is called out as
interim so it is not mistaken for the final design.

Keeping blocks uncompressed with a reserved type byte (C2) obeys N1 and costs nothing later: the codec
slots into an already-present field. Adding an explicit version field (D2) departs from LevelDB by one
`fixed32` in exchange for compliance with our own §2 rule that a version is read before anything else —
cheap insurance against a future silent format change.

## Consequences

**Positive:** the engine gains durable, sorted, immutable on-disk tables in the exact shape M4–M7
extend; the memtable → SSTable → WAL-delete cycle finally *drains* the immutable memtables M2 left
accumulating; the format is documented, golden-pinned, round-tripped, and bit-flip-tested before it is
trusted.

**Negative:** this is a `Reversible: no` on-disk format — every future change pays the §3 tax. The M3
read path is naive (check each SSTable; a simple merge for scans), carrying avoidable work until M4's
heap iterator replaces it. Synchronous flush stalls the writing thread; directory-scan discovery is
throwaway. Reference counting exists but its delete-on-zero lifecycle is only completed at M5.

**Neutral:** the metaindex block is dead weight (empty) until M7. The 52-byte footer is 4 bytes larger
than LevelDB's 48 because of the version field.

**If we need to reverse this:** the format lives entirely in `dev.shale.sstable` and its `format.md`.
Because the footer carries metaindex + index handles and a version, the anticipated M7 change (a filter
block referenced from the metaindex) needs no footer-layout change — only a new metaindex entry and a
`FORMAT_VERSION` bump with a golden file per §3. A deeper change (e.g. a different block entry
encoding) is a breaking format change: allowed before this milestone's tag, and thereafter only via a
migration or a fresh database.

## References

- LevelDB `doc/table_format.md`, `table/block_builder.cc`, `table/format.{h,cc}` — the block table,
  restart points, `BlockHandle`, and the 48-byte footer we extend.
- Petrov, *Database Internals*, ch. 3 (file formats: binary encoding, slotted layout, checksumming).
- Bigtable (OSDI 2006) — the SSTable concept.
- `documentation/conventions/on-disk-formats.md` §1–§5; `naming.md` §7 (file numbering).
- [[0004-internal-key-encoding]], [[0005-little-endian-fixed-integers]], [[0009-skiplist-memtable]]
