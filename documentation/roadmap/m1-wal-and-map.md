# M1 — WAL + in-memory map: implementation plan

**Goal:** make the engine durable. A LevelDB block-structured WAL (ADR-0007) records every
mutation before it is applied to an in-memory map; on reopen the log is replayed. Explicit
`Durability` (ADR-0008) gives the sync/throughput knob, and a `kill -9`-style crash test
proves no acknowledged write is lost under `SYNC`.

**Depends on:** M0 (`StorageBackend`, `InternalKey`/`ValueType`, `LittleEndian`, exceptions).

**Design locked in:** ADR-0007 (WAL block-log format), ADR-0008 (durability + clock),
`shale-core/src/main/java/dev/shale/wal/format.md`.

## Scope

The M1 "in-memory map" is a plain sorted map keyed by encoded internal key — **not** the
hand-written skiplist (that is M2) — behind a `Memtable` seam so M2 swaps the implementation
without touching the engine.

**In M1:** varints, CRC32C helper, injected `Clock`, `Metrics` interface + `wal.*` emitters,
`RecordType`, `WalWriter` (block fragmentation, fsync per `Durability`, group commit),
`WalReader` (fragment reassembly, CRC verify, torn-tail → `RecoveryPolicy`), the mutation
payload codec, a `Memtable` seam + `TreeMemtable`, the engine (`StorageBackend` impl) with
`open()`/recovery, a golden file + round-trip + bit-flip corruption tests, and crash tests.

**Deferred:** skiplist + arena (M2); segment deletion after flush (needs SSTable, M3);
`WriteBatch` atomic groups and snapshots (M7); leveled everything else.

## Task order (TDD; each task one commit, gate green)

1. **Design docs** — ADR-0007, ADR-0008, `wal/format.md`, index. *(done in the first commit)*
2. **Varints** — `dev.shale.internal.coding.Varints`: varint32/64 encode/decode with a
   read cursor; round-trip property test; overlong/truncated → `CorruptionException`.
3. **Crc32c** — thin helper over `java.util.zip.CRC32C` for `byte[]`/segment ranges; test
   against a known vector.
4. **Clock** — `dev.shale.Clock` (`nanoTime`, `epochMillis`), `SystemClock`, `ManualClock`
   (test). The single audited `System.nanoTime()` call site.
5. **Metrics** — `dev.shale.Metrics` (`increment`/`gauge`/`record`) + `NOOP` + a recording
   test double; assert names/values.
6. **RecordType** — enum `ZERO/FULL/FIRST/MIDDLE/LAST` with codes; `fromCode` rejects unknown.
7. **WalWriter** — append a payload as one or more fragments across 32 KiB blocks; file
   header; `force()` per `Durability`; `// DURABILITY:` at the force; `wal.append/sync/*`
   metrics; group commit (leader/follower). Tests: single FULL record, a record that spans
   blocks (FIRST/…/LAST), block-tail zero padding, NONE vs SYNC force counts (via a spy).
8. **WalReader** — read the header (magic/version/reserved), iterate fragments, verify CRC,
   reassemble, and report a torn/truncated tail to a `RecoveryPolicy`. Tests: round-trip vs
   the writer; **golden file** decode; **bit-flip at every offset** all detected; torn tail
   at the exact end → truncate-and-continue, torn in the middle → `CorruptionException`.
9. **Mutation codec** — encode/decode `(InternalKey, value)` payload (§4 of format.md);
   round-trip property test.
10. **Memtable seam** — `dev.shale.memtable.Memtable` (put internal key + value, get, range
    iterator, `sizeBytes`) + `TreeMemtable`; tests incl. tombstone hiding and newest-wins.
11. **Engine** — a `StorageBackend` implementation composing a `WalWriter` + `Memtable` +
    a `SequenceNumber` source; `open(dir, options)` replays existing segments via `WalReader`
    into a fresh memtable; `put/delete` write the WAL (ordering D3) then the memtable; `get`/
    `scan` read the memtable. Tests: get-after-put, overwrite, delete, and reopen-recovers.
12. **Crash tests** (`@Tag("crash")`) — a fault-injecting file seam: truncate a segment at
    every byte offset and reopen; assert the engine opens with a prefix of the acknowledged
    writes and never both-partly (N4); under `SYNC`, every acknowledged write survives.
13. **Model harness** — add `reopen` to the op mix in a new `@Tag("model")` test driving the
    real engine against `ReferenceModel`, comparing after every restart (testing.md §1).
14. **Finish** — full `./gradlew build` + `crashTest` green; `Format-Change:` not needed (new
    format, first version); release note; tag `m1-wal`.

## Invariants to hold (checked by tests, not just prose)

- **D3 ordering:** WAL append (+force for SYNC/GROUP) precedes the memtable update, which
  precedes acknowledgement.
- **N4:** a checksum/structure mismatch anywhere but the exact tail throws `CorruptionException`
  with offset/expected/actual — never a silent skip.
- **N2:** the golden file is never regenerated to make a test pass.
- **N8/testing §2:** no `Thread.sleep`; group-commit tests use latches and the `ManualClock`.
