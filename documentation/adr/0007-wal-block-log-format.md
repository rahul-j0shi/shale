# 0007. Block-structured log format for the WAL

- **Status:** Accepted
- **Date:** 2026-07-28
- **Milestone:** M1
- **Reversible:** no — this is the byte layout of every write-ahead log segment; changing it after the first tagged M1 release makes existing logs unreadable.

## Context

The write-ahead log is the durability substrate: a record must be on disk before its write
is acknowledged, and after a crash the log is replayed to reconstruct the in-memory state
([[0008-durability-and-clock]]). The format must therefore be **robust to a torn tail** — a
process or power failure mid-append leaves a partial record — and must let recovery tell a
clean end-of-log from corruption (N4). It also has to obey the universal on-disk rules
(`on-disk-formats.md` §2): a file magic, a version field read first, a CRC32C over every
record, and a length that is bounds-checked before it is trusted.

The roadmap describes the record as "length + CRC + **type** + payload" in "rolled,
immutable segments" — the LevelDB log vocabulary.

## Options considered

### Option A — LevelDB block-structured log
Fixed 32 KiB physical blocks. A logical record is split into one or more fragments, each
with a 7-byte header `CRC32C(4) · length(2) · type(1)` where type is `FULL` (whole record
in one fragment) or `FIRST`/`MIDDLE`/`LAST` (record spans blocks). When fewer than 7 bytes
remain in a block, the trailer is zero-padded and the next fragment starts a fresh block.
Used by LevelDB, RocksDB, and Pebble.

### Option B — Length-prefixed records, no blocks
Each record is `length · CRC32C · payload`, appended straight into a size-rolled segment.
Simpler to write and read (no fragmentation), but a corrupt length in the middle of a
segment desynchronises the reader with no block boundary to resynchronise on.

### Option C — Kafka-style segment with an index
A record log plus a sparse offset index per segment. Overkill: we replay whole segments
sequentially at recovery and never seek by offset within one.

## Decision

**Option A**, with a small file header prepended to satisfy our universal rules (LevelDB's
log has no file header).

Layout (full byte spec and a worked hex example in `shale-core/src/main/java/dev/shale/wal/format.md`):

- **File header (16 bytes)** at offset 0: `magic` `fixed64LE = 0x5368616C6557414C` ("ShaleWAL"),
  `FORMAT_VERSION` `fixed32LE = 1`, then 4 reserved bytes written zero and rejected if
  non-zero. The block stream begins at offset 16.
- **Block** = 32768 bytes. Fragments are laid end to end; a `< 7`-byte tail is zero-padded.
- **Fragment header (7 bytes)**: `CRC32C(4, LE)` over `type ‖ payload`, `length(2, LE)` of
  the payload, `type(1)`. Types: `ZERO=0` (padding / preallocated, never a real record),
  `FULL=1`, `FIRST=2`, `MIDDLE=3`, `LAST=4`.
- **CRC32C** is `java.util.zip.CRC32C` (Castagnoli) — a JDK primitive, not a project
  subject, so it is used directly (java-style.md §1). Stored unmasked.
- **Payload** of a reassembled logical record is one mutation:
  `internalKeyLen(varint32) ‖ internalKey ‖ valueLen(varint32) ‖ value`, where `internalKey`
  is the M0 encoding ([[0004-internal-key-encoding]]) carrying the user key, sequence
  number, and value type; `value` is empty for a `DELETE`.
- **Segments**: files named `NNNNNN.wal` (six-digit, from the shared monotonic file-number
  counter, naming.md §7). One active segment; it rolls to a new segment at a configured
  size, and covered segments are deleted only after the memtable they cover is flushed (M3).

## Rationale

The block structure is the whole point: a torn write damages at most the last fragment of
one block, and the reader **resynchronises at the next 32 KiB boundary** rather than losing
the rest of the segment — which is exactly the property a length-prefixed stream (Option B)
lacks. Fragmentation lets a record larger than a block still be logged without a variable
block size. Matching LevelDB's framing keeps the format, and the reader/writer, directly
comparable to the reference implementations this project studies, and reuses the M0
internal-key encoding for the payload so the WAL, memtable, and (later) SSTable all speak
one key format. The 16-byte file header is the minimum needed to honour the magic/version
rule without disturbing the block math (blocks are measured from offset 16).

CRC over `type ‖ payload` (not the length) matches LevelDB; the length is validated against
the remaining block extent before the payload is read, so a corrupt length can never drive
an over-large allocation (`on-disk-formats.md` §2).

## Consequences

**Positive:** torn-tail robustness with block-granular resync; records unbounded by block
size; one shared key encoding across the engine; grep-comparable to LevelDB/RocksDB/Pebble.

**Negative:** the reader must reassemble fragments and track partial-record state across
blocks — materially more code than a flat record stream; 6 bytes of framing overhead per
32 KiB block worst case plus a 7-byte header per fragment.

**Neutral:** 32 KiB and the segment-roll size are tunable constants, not format; changing
them does not change how existing bytes are read.

**If we need to reverse this:** no in-place migration — bump `FORMAT_VERSION`, keep a reader
for the old version, and drain old segments by replaying them into a fresh log. Settled at
M1 before any log exists on disk.

## References

- LevelDB `db/log_format.h`, `db/log_writer.cc`, `db/log_reader.cc`
- `documentation/conventions/on-disk-formats.md` §2, §5
- Petrov, *Database Internals*, ch. 5 (recovery) and ch. 3 (file formats)
- [[0004-internal-key-encoding]], [[0005-little-endian-fixed-integers]], [[0008-durability-and-clock]]
