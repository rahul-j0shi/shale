# M3 — SSTable write + flush: implementation plan

**Goal:** serialise an immutable memtable to an **SSTable** on disk (ADR-0010), then delete the
covering WAL segment — finally *draining* the immutable memtables M2 left in memory. Reads consult the
memtable set and the SSTables; reopening loads the tables and replays what WAL remains.

**Depends on:** M2 (the memtable set + immutable handoff), M1 (WAL, `Durability`, `Clock`), M0 (the
`InternalKey` stack, `Varints`, `Crc32c`, `LittleEndian`).

**Design locked in:** ADR-0010 (SSTable block-table format) and
`shale-core/src/main/java/dev/shale/sstable/format.md`. This introduces a new on-disk format —
`Format-Change:` trailer, golden file, round-trip and bit-flip tests all required (on-disk-formats.md
§3).

## Scope

**In M3:**
- `dev.shale.sstable`: `BlockHandle`, `BlockBuilder` (prefix compression + restart points), the block
  reader (restart binary search + iterate), `SSTableWriter` (data blocks → metaindex → index →
  footer), `SSTableReader` (footer/index load, point `get`, iterator, `retain`/`release`), `Footer`.
- A golden `two-puts.sst` fixture + `.json`, a jqwik round-trip property test, and a
  bit-flip-at-every-offset corruption test.
- Engine flush: one monotonic file-number counter shared with WAL segments; each immutable memtable is
  paired with its WAL segment; a synchronous flush writes the SSTable, `force()`s it, then deletes the
  segment (D3 ordering).
- Reads: point `get` and `scan` consult the memtable set then the SSTables, newest first.
- Recovery: directory-scan `NNNNNN.sst`, open readers, replay remaining WAL, derive `nextSequence`
  from both tables and log.
- Metrics: `flush.count`, `flush.bytes`, `sstable.count`.

**Deferred:** the manifest / `CURRENT` and the reference-counted *delete-on-zero* lifecycle (M5); the
heap-based multi-way merge iterator (M4 — M3's `scan` uses a simple materialising merge); block cache
and bloom filters (M7); compression (a later change into the reserved type byte, N1); background flush
+ write stalls (M6 — M3 flush is synchronous); shortest-separator index keys (an optimisation).

## Task order (TDD; each task one commit, gate green)

1. **Design docs** — ADR-0010, `sstable/format.md`, M3 plan, ADR index. *(this commit)*
2. **BlockHandle + BlockBuilder** — encode entries with prefix compression and restart points; the
   block content layout of `format.md` §2. Tests: single entry, shared-prefix compression, a restart
   every 16 keys, empty block.
3. **Block reader** — parse a block, binary-search the restarts, iterate; reconstruct full keys from
   deltas. Tests: round-trip vs the builder; `seek` lands on the first key ≥ target; a truncated or
   CRC-broken block is `CorruptionException` (N4).
4. **SSTableWriter** — buffer a data block until it reaches the block size, flush it, record its last
   key → handle in the index; write the (empty) metaindex, the index, and the footer. Tests: a table
   round-trips through the reader.
5. **SSTableReader** — read+verify the footer (magic, version, zero padding), load the index; `get`
   via index → data block → restart search; a full-table iterator; `retain`/`release` over the file
   handle (N6). Tests: point lookups hit and miss; iteration is ordered; bad magic / bad version /
   non-zero padding are corruption.
6. **Golden + round-trip + bit-flip** — birth `two-puts.sst` with the writer, freeze it, pin its CRCs
   into `format.md`; a golden test decodes it; a jqwik round-trip test; a bit-flip-at-every-offset test
   that asserts every corruption is detected.
7. **Shared file counter + immutable/segment pairing** — generalise the WAL segment counter to one
   `nextFileNumber` shared by `.wal` and `.sst`; pair each immutable memtable with its WAL segment.
8. **Flush** — write the oldest immutable memtable to an SSTable, `force()`, publish the new
   memtable/SSTable set, then delete the WAL segment (D3, marked). Synchronous. Tests: a switch that
   flushes produces a `.sst`, drops the immutable, and removes the `.wal`.
9. **Reads + recovery over SSTables** — `get`/`scan` consult SSTables after the memtables, newest
   first; `open` loads `.sst` files and derives `nextSequence` from tables + WAL. Tests: a key only in
   an SSTable reads back; a tombstone in the memtable hides an SSTable value; reopen after a flush
   recovers everything.
10. **Harness** — extend the engine model test to force flushes (tiny buffer) and restart, diffing
    against the oracle; extend/verify the crash test still holds with a flush in the mix.
11. **Docs** — `sstable/package-info.java`; glossary rows if any new term; the M3 as-built architecture
    doc (block/index/footer, flush + WAL-delete ordering, read merge, recovery), mermaid validated;
    update the engine `package-info` (flush ordering).
12. **Finish** — full `./gradlew build` + `crashTest` green; README status; M3 release note; tag
    `m3-sstable`. Commit the format work with `Format-Change: sstable v1` and `Reversible: no`.

## Invariants to hold (checked by tests, not just prose)

- **N2/§3:** the golden file is never regenerated to pass; the reader reads it; every bit-flip in a
  small table is detected.
- **N4:** any CRC or structural mismatch (bad magic, bad version, non-zero padding, overrun length) is
  `CorruptionException` with the offset — never a silent skip.
- **D3:** the SSTable is `force()`d durable **before** its WAL segment is deleted; a crash between the
  two leaves both, and recovery is idempotent (the data is in the table *or* the log, never lost).
- **Read correctness:** the newest version of a key wins across memtables and SSTables; a tombstone
  shadows an older SSTable value; a flush changes no read result.
- **Recovery:** reopening after any number of flushes yields exactly the acknowledged writes.
