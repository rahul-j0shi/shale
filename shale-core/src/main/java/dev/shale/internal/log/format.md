# Log framing

The block framing shared by every Shale log: the WAL now, the M5 manifest later. It splits a
file into 32 KiB blocks and each logical record into checksummed fragments, after LevelDB's log
(ADR-0007). This page owns the framing only. What a log's file header names (its magic) and what
its records contain belong to the log that uses it, for example
[`wal/format.md`](../../wal/format.md).

All integers are little-endian (ADR-0005). The checksum is CRC32C (Castagnoli,
`java.util.zip.CRC32C`), unmasked.

| Version | Fragment header | Status | Decision |
|---|---|---|---|
| 1 | 7 bytes: `payloadCrc ‖ length ‖ type` | readable; never written from M4.5 | ADR-0007 |
| 2 | 11 bytes: v1's 7, then `headerCrc` | current | ADR-0024 |

---

## 1. File header (16 bytes, at offset 0)

| Offset | Size | Type | Field | Notes |
|---|---|---|---|---|
| 0 | 8 | fixed64LE | magic | names the log, e.g. `ShaleWAL` |
| 8 | 4 | fixed32LE | version | the framing version above (and the log's own version) |
| 12 | 4 | zeros | reserved | written zero, rejected if non-zero |

The block stream begins at offset 16. Block *i* occupies file bytes `[16 + i·32768,
16 + (i+1)·32768)`.

## 2. Blocks and fragments

A block is a run of fragments laid end to end. When fewer bytes remain in a block than a fragment
header needs (7 in v1, 11 in v2), they are **zero padding**, and the next fragment starts the next
block. Padding is verified to be zero on read.

A logical record is one `FULL` fragment, or `FIRST`, zero or more `MIDDLE`, then `LAST`.

### 2.1 Fragment header, version 2 (11 bytes)

| Offset | Size | Type | Field | Notes |
|---|---|---|---|---|
| 0 | 4 | fixed32LE | payloadCrc | CRC32C over the `type` byte, then the payload |
| 4 | 2 | fixed16LE | length | payload bytes; at most 32768 − 11 = 32757 |
| 6 | 1 | uint8 | type | `1 FULL · 2 FIRST · 3 MIDDLE · 4 LAST`; `0` is never written |
| 7 | 4 | fixed32LE | headerCrc | CRC32C over bytes 0–6 of this header |
| 11 | length | bytes | payload | this fragment of the logical record |

The first seven bytes are exactly a v1 header. `headerCrc` is what v2 adds: every field the
reader uses to classify a fragment is now checked before it is used.

### 2.2 Fragment header, version 1 (7 bytes)

The first seven bytes of §2.1, with no `headerCrc`; at most 32768 − 7 = 32761 payload bytes.
Type `0` (`ZERO`) is accepted only as the start of an all-zero unwritten tail (§3). Version 1 is
read, never written.

## 3. How a reader classifies what it finds (ADR-0024 §2)

Starting after the last good fragment, the **first row that matches decides**:

| At the reader's position | v2 | v1 |
|---|---|---|
| fewer bytes left than a fragment header | torn header: **torn tail** | torn header: torn tail |
| every byte from here to the end is zero | unwritten tail: **torn tail** | unwritten tail: torn tail |
| `headerCrc` mismatch | **corruption** | — (no header checksum) |
| type not 1–4 (v1: including `ZERO`) | corruption | corruption |
| `length` larger than the block has room for | corruption | corruption |
| the payload runs past the end of the file | torn payload: **torn tail** | **unverifiable** (ADR-0024 §3; M4.5 Step 5) |
| `payloadCrc` mismatch | corruption | corruption |
| fragment out of sequence (`MIDDLE` with no `FIRST`, …) | corruption | corruption |
| the file ends inside an open `FIRST…` record | torn tail | torn tail |
| padding byte not zero | corruption | corruption |

**Why the order matters.** The all-zero check comes before the header checksum because a header
of seven zero bytes has CRC32C `0xBB3E6A6D`, so an all-zero v2 header can never pass its own
check: without the earlier row, a filesystem's zero-filled tail would read as corruption. The
"payload past the end" row comes after the header checksum in v2, so the `length` it relies on
has been verified. In v1 that `length` is unverified, which is exactly how one flipped length bit
once passed for a torn tail (SH-18).

A torn tail is never corruption: the bytes were never fully written, so no record in them was
acknowledged. Whether a reader *accepts* one is its caller's policy (`TRUNCATE_TAIL` or `STRICT`
for the WAL), and only the newest WAL segment may have one (ADR-0024 §2).

## 4. Worked example — one v2 `FULL` fragment

The WAL's single-put payload ([`wal/format.md`](../../wal/format.md) §5): `key` → `v`, sequence
1, 14 bytes.

```
F3 27 F3 CC               payloadCrc = crc32c(01 ‖ payload)   (the same bytes as v1)
0E 00                     length = 14
01                        type = FULL
1E D1 FE C4               headerCrc = crc32c(F3 27 F3 CC 0E 00 01)
0B 6B 65 79 01 01 00 00   payload ...
00 00 00 01 76
```

Both CRCs were computed by an implementation independent of Shale's, before the v2 encoder
existed. The golden files `golden/wal/v2/*.wal` pin them, and `GoldenWalTest` decodes them.

## 5. Rationale and cost

See ADR-0024. Four bytes per fragment: a 30-byte record grows from 37 to 41 bytes (about 11%); a
full 32 KiB fragment by 0.01%. The CRC over seven bytes is negligible beside the payload's and
the fsync. CRC32C detects accidental damage; it is not authentication.

## 6. Version history

| Version | Milestone | Status | Change |
|---|---|---|---|
| 1 | M1 | readable | LevelDB block framing with a 16-byte file header (ADR-0007) |
| 2 | M4.5 | current | 4-byte `headerCrc` after each fragment header (ADR-0024) |
