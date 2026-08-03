# M3 — SSTable write + flush (release note)

**Tag:** `m3-sstable` · **Status:** complete, `./gradlew build` + `crashTest` green on JDK 25.

## Delivered

The engine now has durable, sorted, on-disk tables, and the memtable is flushed to them.

- **SSTable format (`dev.shale.sstable`, ADR-0010):** a LevelDB block table — prefix-compressed data
  blocks with restart points and a per-block CRC32C trailer, a last-key index block, an (empty,
  M7-ready) metaindex block, and a 52-byte footer carrying both handles, an explicit `FORMAT_VERSION`,
  and the `"ShaleSST"` magic. `BlockBuilder`/`Block`, `BlockHandle`, `Footer`, `SSTableWriter`
  (temp-file + atomic rename, `force()` in `finish`), and `SSTableReader` (footer/index load, ceiling
  lookup, iteration, reference-counted over its `FileChannel`, N6). Byte layout in `format.md`, pinned
  by the frozen golden `two-puts.sst`.
- **Flush (`dev.shale.Shale`):** a full active memtable is frozen, written to an SSTable, and its WAL
  segment reclaimed — the SSTable is fsync'd and atomically installed **before** the segment is
  deleted (D3). Synchronous at M3. Emits `flush.count` / `flush.bytes`.
- **Reads across sources:** point `get` consults the active memtable, immutable memtables, then the
  SSTables (newest first); a memtable tombstone shadows a flushed value. `scan` merges every source,
  newest-per-key.
- **Recovery:** loads existing tables, replays remaining WAL into a memtable, flushes it to a fresh
  table, and deletes the replayed segments; a `*.sst.tmp` from a crashed flush is ignored and cleaned
  up. File numbers come from one counter shared by `.wal` and `.sst`.

## Exit criteria (met)

- `./gradlew build` green; `crashTest` green; `shale-core` still has zero runtime deps (N1).
- **Format discipline (on-disk-formats.md §3):** a golden fixture (never regenerated), a jqwik
  round-trip property test, and a bit-flip-at-every-offset test asserting each corruption is detected
  or provably harmless (N4). *(Also fixed: the blanket `*.wal`/`*.sst` `.gitignore` rules had silently
  dropped both golden binaries from the repo — the M1 WAL golden included.)*
- **End to end:** the engine model test drives the real engine through constant flushes and restarts
  against a `TreeMap` oracle; the crash test truncates the WAL at every offset and still recovers a
  clean prefix.

## Notes for the next milestone

M4 replaces the M3 materialising merge with a **heap-based multi-way merge iterator** across the
memtable set and many SSTables — proper streaming `get`/`scan` reconciliation with tombstones. M5 adds
the **manifest** (atomic version install, `CURRENT`, comparator-name check) and the reference-counted
*delete-on-zero* file lifecycle, retiring the directory-scan discovery and the recovery-flush. Block
cache and bloom filters (M7) attach to the block/metaindex structure this milestone laid down;
compression slots into the reserved block-type byte.
