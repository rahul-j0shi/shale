# M1 — WAL + in-memory map (release note)

**Tag:** `m1-wal` · **Status:** complete, `./gradlew build` + `crashTest` green on JDK 25.

## Delivered

The engine is durable. Every mutation is logged to a write-ahead log before it touches the
in-memory map, and reopening replays the log to recover.

- **WAL (`dev.shale.wal`, ADR-0007):** a LevelDB block-structured log — 32 KiB blocks, 7-byte
  fragment header (CRC32C · length · type), `FULL`/`FIRST`/`MIDDLE`/`LAST` for records that
  span blocks, and a 16-byte file header (magic + version). `WalWriter` fragments and forces
  per `Durability`; `WalReader` reassembles, verifies every CRC, and distinguishes a truncated
  tail (→ `RecoveryPolicy`) from a bit-flip (→ `CorruptionException`, N4). Byte layout in
  `wal/format.md`, pinned by a frozen golden file.
- **Durability (ADR-0008):** `NONE` / `SYNC` / `GROUP`, chosen per write, with the `fsync`
  marked `// DURABILITY:` and `wal.*` metrics. An injected `Clock` (`SystemClock` the single
  audited `System.nanoTime()` site; `ManualClock` for tests) times syncs.
- **Engine (`dev.shale.Shale`):** the first durable `StorageBackend` — WAL-before-memtable
  ordering (D3), `open()` replay-recovery over numbered segments, `put`/`delete`/`get`/`scan`.
- **Memtable seam (`dev.shale.memtable`):** `Memtable` + `TreeMemtable` (the M1 sorted map);
  M2 swaps in the hand-written skiplist without touching the engine.
- **Primitives:** `Varints` (varint32/64), `Crc32c`, `Clock`, `Metrics`, and `LittleEndian`
  `fixed32`.

## Exit criteria (met)

- `./gradlew build` green; `crashTest` green; `shale-core` still has zero runtime deps (N1).
- **Crash test:** truncating the WAL at *every* byte offset and reopening always recovers a
  clean prefix of the writes — never a wrong or partial value, and no acknowledged (`SYNC`)
  write is lost (testing.md §1, N4).
- 61 unit/model/property test cases plus the crash suite; round-trip, a record spanning
  multiple blocks, bit-flip, torn-tail under both policies, bad magic, and reopen-recovers.

## Notes for the next milestone

M2 replaces `TreeMemtable` with a hand-written concurrent skiplist on an arena, adds memory
accounting and the immutable-memtable handoff, and removes the M1 simplification where reads
serialise with writes. The true leader/follower group-commit batching (ADR-0008 marks the
mechanism revisable) and segment rotation/deletion (needs flush, M3) are deferred; M1's
`GROUP` currently forces before ack like `SYNC`, which satisfies the guarantee.
