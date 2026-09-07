# Manifest on-disk format

The record of **which files the database consists of** (ADR-0012). A manifest is an
append-only log of `VersionEdit` records; the current version is the fold of every edit in
the file, applied in order. A separate `CURRENT` file names the active manifest.

Framing is the WAL's block log (ADR-0007) with a different magic — same blocks, same
fragment header, same CRC32C, same torn-tail semantics. Only §4's payload is new. All
integers little-endian (ADR-0005); all lengths and counts are LevelDB varints.

- **Magic:** `0x5368616C654D414E` ("ShaleMAN"), `fixed64LE`.
- **FORMAT_VERSION:** `1`.
- **Block size:** `32768` bytes, as the WAL.
- **Checksum:** CRC32C, unmasked, over `type ‖ payload`, as the WAL.
- **File naming:** `NNNNNN.manifest`, from the same file-number counter as `.wal` and `.sst`.

---

## 1. File header (16 bytes, at offset 0)

| Offset | Size | Type      | Field          | Notes                              |
|--------|------|-----------|----------------|------------------------------------|
| 0      | 8    | fixed64LE | magic          | `0x5368616C654D414E`               |
| 8      | 4    | fixed32LE | FORMAT_VERSION | `1`                                |
| 12     | 4    | zeros     | reserved       | written zero, rejected if non-zero |

Identical in shape to the WAL header, so a file opened with the wrong reader fails on the
magic rather than on a misparsed record.

## 2. Block, and 3. Fragment header

Unchanged from [`../wal/format.md`](../wal/format.md) §2–§3. One logical record — one
`VersionEdit` — is fragmented across blocks as `FULL`, or `FIRST ‖ MIDDLE* ‖ LAST`.

## 4. Logical record payload = one VersionEdit

A sequence of tagged fields, read until the payload is exhausted. A field absent from an
edit means *unchanged*; the version is the fold of every edit before it.

| Tag | Field            | Value encoding                                                                 |
|-----|------------------|--------------------------------------------------------------------------------|
| 1   | comparator name  | varint length ‖ UTF-8 bytes                                                     |
| 2   | log number       | varint64 — WAL segments numbered **below** this are covered by a flush          |
| 3   | next file number | varint64                                                                        |
| 4   | last sequence    | varint64                                                                        |
| 5   | deleted file     | varint32 level ‖ varint64 file number                                           |
| 6   | added file       | varint32 level ‖ varint64 file number ‖ varint64 size ‖ *key* smallest ‖ *key* largest |

where *key* = varint length ‖ encoded internal key bytes (ADR-0004).

Tags 5 and 6 may repeat within one edit; the others appear at most once. **An unknown tag is
`CorruptionException`** with its offset — never a skipped field. A manifest says which files
exist, so ignoring an unrecognised field could silently drop one (N4). The format is
therefore deliberately *not* forward compatible: an older reader must refuse a newer
manifest, not guess at it.

`level` is written for every file and is always `0` until compaction (M6) creates deeper
levels. It is present from v1 so that M6 needs no format change.

## 5. The `CURRENT` file

A single line: the active manifest's file name, then `\n`. No header, no checksum — it is
rewritten whole, never appended to.

```
000007.manifest\n
```

Replaced by writing `CURRENT.tmp`, `force()`ing it, then `ATOMIC_MOVE` over `CURRENT`. The
rename is the commit point for *discovery*, exactly as the SSTable's rename is for a table.

| State on disk                          | Meaning                                   |
|----------------------------------------|-------------------------------------------|
| no `CURRENT`, no manifest              | a new database — create both              |
| `CURRENT` naming an existing manifest  | open it and replay                        |
| `CURRENT` naming a **missing** manifest | corruption (N4) — never "empty database"  |
| `CURRENT.tmp` present                  | a crash mid-rewrite; ignore and delete it |

## 6. Worked example — a fresh database's first manifest

One edit: comparator `"shale.BytewiseComparator"` (24 bytes), log number 2, next file number
3, last sequence 0, no files yet.

Payload (32 bytes):

```
01 18 73 68 61 6C 65 2E 42 79 74 65 77 69 73 65   tag1, len=24, "shale.Bytewise
43 6F 6D 70 61 72 61 74 6F 72 02 02 03 03 04 00   Comparator", then 02 02 = log
                                                  number 2, 03 03 = nextFile 3,
                                                  04 00 = lastSequence 0
```

Wrapped as one `FULL` fragment, the record on disk is:

```
offset 16:  .. .. .. ..   crc32c(type ‖ payload), fixed32LE
offset 20:  20 00         length = 32
offset 22:  01            type   = FULL
offset 23:  01 18 73 ...  the 32 payload bytes above
```

The file is 16 (header) + 7 (fragment header) + 32 = **55 bytes**, and `CURRENT` holds
`000001.manifest\n`.

The CRC is shown as `.. .. .. ..` deliberately: the committed golden fixture is the authority
for it, not this table. See `shale-core/src/test/resources/golden/manifest/v1/`, whose `.json`
sibling states the expected decode. A golden file is never regenerated to make a test pass
(on-disk-formats.md §4).

## 7. Rationale

See ADR-0012. Reusing the WAL's framing means the manifest inherits an already-golden,
already-bit-flip-tested parser and the torn-tail policy it needs: a partial edit at the tail
is a crash *during* an install and must be discarded, because the install never completed and
was never acknowledged; a bad CRC anywhere else is corruption.

Every open rewrites the manifest, starting the new one with a full snapshot of the live file
set, so a manifest's length is bounded by one run rather than by the lifetime of the database.

## 8. Version history

| Version | Milestone | Status  | Change                    |
|---------|-----------|---------|---------------------------|
| 1       | M5        | current | Initial manifest edit log |
