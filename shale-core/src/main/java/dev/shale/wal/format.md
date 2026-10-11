# WAL on-disk format

Block-structured write-ahead log, LevelDB-style (ADR-0007). All integers little-endian
(ADR-0005). One active segment; segments are named `NNNNNN.wal`.

- **Magic:** `0x5368616C6557414C` ("ShaleWAL"), `fixed64LE`.
- **FORMAT_VERSION:** `2` written; `1` and `2` read.
- **Framing:** blocks, fragments and checksums are the shared log framing, specified in
  [`internal/log/format.md`](../internal/log/format.md): version 2 adds a checksum over each
  fragment header (ADR-0024). This page owns the WAL's file header and record payload.

---

## 1. File header (16 bytes, at offset 0)

| Offset | Size | Type       | Field          | Notes                                   |
|--------|------|------------|----------------|-----------------------------------------|
| 0      | 8    | fixed64LE  | magic          | `0x5368616C6557414C`                     |
| 8      | 4    | fixed32LE  | FORMAT_VERSION | `2` (`1` in segments written before M4.5) |
| 12     | 4    | zeros      | reserved       | written zero, rejected if non-zero       |

The block stream begins at offset 16. Block *i* occupies file bytes
`[16 + i·32768, 16 + (i+1)·32768)`.

## 2. Blocks

32768 bytes each, holding fragments end to end; a block tail too short for a fragment header is
zero padding. See [`internal/log/format.md`](../internal/log/format.md) §2.

## 3. Fragment header — version 1, kept for reading old segments (v2: [`internal/log/format.md`](../internal/log/format.md) §2.1)

| Offset | Size | Type       | Field    | Notes                                         |
|--------|------|------------|----------|-----------------------------------------------|
| 0      | 4    | fixed32LE  | crc32c   | CRC32C over the `type` byte then the payload  |
| 4      | 2    | fixed16LE  | length   | payload byte count (≤ 32768 − 7 = 32761)      |
| 6      | 1    | uint8      | type     | `0 ZERO · 1 FULL · 2 FIRST · 3 MIDDLE · 4 LAST`|
| 7      | len  | bytes      | payload  | fragment of the logical record                |

`length` is validated against the bytes remaining in the block **before** the payload is
read (`on-disk-formats.md` §2). A logical record is `FULL`, or `FIRST` then zero or more
`MIDDLE` then `LAST`.

## 4. Logical record payload = one mutation

| Field          | Type       | Notes                                             |
|----------------|------------|---------------------------------------------------|
| internalKeyLen | varint32   | length of the encoded internal key                |
| internalKey    | bytes      | M0 encoding: userKey ‖ trailer(seq, valueType)    |
| valueLen       | varint32   | value byte count; `0` for a `DELETE`              |
| value          | bytes      | absent when `valueLen == 0`                        |

Varints are LevelDB varint32/64 (`dev.shale.internal.coding`). The internal key encoding is
ADR-0004.

## 5. Worked example — one PUT (`key` → `v`, seq 1), in a v1 segment

The same payload in a v2 fragment is worked in [`internal/log/format.md`](../internal/log/format.md) §4.

Internal key = `6B 65 79` (`"key"`) ‖ trailer `fixed64LE((1<<8)|PUT=0x0101)` =
`6B 65 79 01 01 00 00 00 00 00 00` (11 bytes).

Payload (14 bytes):
```
0B                                            internalKeyLen = 11
6B 65 79 01 01 00 00 00 00 00 00              internalKey
01                                            valueLen = 1
76                                            value = "v"
```

Fragment (type FULL): `crc32c(01 ‖ payload)` (4 LE) · `0E 00` (length 14) · `01` (FULL) · payload.

File bytes:
```
4C 41 57 65 6C 61 68 53   magic  (0x5368616C6557414C, LE)
01 00 00 00               FORMAT_VERSION = 1
00 00 00 00               reserved
F3 27 F3 CC               crc32c(type ‖ payload)   [pinned by the golden file]
0E 00                     length = 14
01                        type = FULL
0B 6B 65 79 01 01 00 00   payload ...
00 00 00 01 76
```

The CRC bytes above are fixed by the committed golden file
`shale-core/src/test/resources/golden/wal/v1/single-put.wal` and its `.json` sibling; the
golden test decodes it and asserts the logical contents (`on-disk-formats.md` §4).

**No bit-flip test exists for v1.** Until 2026-10-10 this page claimed one. Its absence hid two
faults; see §5a.

## 5a. Why v1 is no longer written (ADR-0024)

The fragment CRC covers `type ‖ payload`, not `length`, and the reader decides "torn tail" from
fields the CRC does not cover:
- a `FULL` → `ZERO` type bit skips the block before any CRC check (SH-08);
- a length bit that pushes the payload past the end of the file is read as a torn tail (SH-18).

Either way, recovery succeeded without acknowledged records. A payload CRC cannot settle the
length case, because the claimed payload is not in the file. Version 2 adds a header checksum,
so every field used to classify a fragment is verified first. v1 segments are still read, under
a conservative rule: a `ZERO` type is accepted only as an all-zero unwritten tail (fixed for
SH-08), and a complete v1 header with a short payload is an *unverifiable tail* that fails
`open` rather than being dropped (M4.5 Step 5). The v1 goldens `two-puts.wal`, `fragmented.wal`
and `block-padding.wal` pin the bytes these rules read.

## 6. Rationale

See ADR-0007. Block framing bounds torn-tail damage to one block and lets the reader
resynchronise at the next boundary; the payload reuses the M0 internal-key encoding so the
WAL, memtable, and SSTable share one key format.

## 7. Version history

| Version | Milestone | Status  | Change                          |
|---------|-----------|---------|---------------------------------|
| 1       | M1        | readable | Initial block-structured format |
| 2       | M4.5      | current  | Log framing v2: a CRC32C over each fragment header (ADR-0024); file header and payload unchanged |
