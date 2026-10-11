# Manifest on-disk format

The manifest is the durable record of which files make up the database: a log of **version
edits**, each the difference between one set of live files and the next (ADR-0012, after
LevelDB's `MANIFEST`). `CURRENT` names the live manifest. All integers are little-endian
(ADR-0005); varints are LevelDB's (`dev.shale.internal.coding.Varints`).

- **Magic:** `0x5368616C654D414E` ("ShaleMAN"), `fixed64LE`.
- **FORMAT_VERSION:** `2`, written and read. There is no version 1 (§6).
- **Framing:** the shared log framing v2 — 32 KiB blocks of fragments, each with a CRC32C over
  its header and another over its payload — specified in
  [`internal/log/format.md`](../internal/log/format.md). This page owns the file header's magic,
  the record payload, and the manifest's tail rule.

---

## 1. Files

| Name | Content | Written how |
|---|---|---|
| `MANIFEST-NNNNNN` | this format | created with one full-state edit and synced; then one edit appended and synced per change |
| `CURRENT` | one line: `MANIFEST-NNNNNN\n` (ASCII, the number at least six digits) | written to `CURRENT.tmp`, synced, renamed over `CURRENT`, directory synced |

`NNNNNN` is a file number from the engine's one counter, `%06d` as a minimum width.

## 2. File header (16 bytes, at offset 0)

| Offset | Size | Type | Field | Notes |
|---|---|---|---|---|
| 0 | 8 | fixed64LE | magic | `0x5368616C654D414E`: bytes `4E 41 4D 65 6C 61 68 53` |
| 8 | 4 | fixed32LE | FORMAT_VERSION | `2`: the log framing version |
| 12 | 4 | zeros | reserved | written zero, rejected if non-zero |

## 3. Record payload = one `VersionEdit`

A sequence of `(tag, field)` pairs, tag first, until the payload ends. Tags follow LevelDB's
`db/version_edit.cc` numbering; Shale does not use LevelDB's tag 9 (previous log number).

| Tag (varint32) | Field | Encoding | At most |
|---|---|---|---|
| 1 | comparator name | varint32 length ‖ UTF-8 bytes | once |
| 2 | log number: the oldest WAL segment still needed | varint64 | once |
| 3 | next file number | varint64 | once |
| 4 | last sequence | varint64 | once |
| 5 | compaction pointer | varint32 level ‖ varint32 length ‖ internal key | repeated |
| 6 | deleted file | varint32 level ‖ varint64 file number | repeated |
| 7 | new file | varint32 level ‖ varint64 number ‖ varint64 size bytes ‖ varint64 smallest sequence ‖ varint64 largest sequence ‖ varint32 length ‖ smallest internal key ‖ varint32 length ‖ largest internal key | repeated |

The writer emits the tags in ascending order, and repeated ones in the order the edit lists them.
A reader accepts them in any order.

**Tag 7 carries each file's sequence range**, as RocksDB's `FileMetaData` does and LevelDB's does
not: once compaction writes new files (M6), a file's number no longer says how new its data is.
**Tag 5** is defined now, written first by M6, so M6 needs no format change.

### 3.1 What a reader rejects (ADR-0024 §6)

Each of these is `CorruptionException`, naming the manifest:

- an unknown tag (adding a tag is a version bump);
- a field that runs past the end of the payload;
- a varint32 field of 2^32 or more; a level outside 0–6;
- tag 1, 2, 3 or 4 appearing twice in one edit;
- an internal key shorter than its 8-byte trailer;
- a smallest sequence greater than the largest;
- a comparator name that is not valid UTF-8.

Lengths are checked against the bytes remaining by subtraction, as `long`s, before anything is
narrowed or allocated.

## 4. Tails

A manifest is classified by the log framing's table (`internal/log/format.md` §3):

- **A torn record at the very end** (a torn header, an unwritten tail, a torn payload, or an
  open `FIRST…` record) is dropped. It was never synced, so it never took effect: an edit takes
  effect only when the sync after its append returns.
- **Anything else** — a header or payload checksum failure, bad padding, an out-of-sequence
  fragment, a damaged file header — is corruption.
- **A file shorter than its header** is corruption: `CURRENT` names a manifest only after its
  header and first edit are synced.

## 5. Worked example — one new-file edit

Level 0, file 12, 1,234 bytes, sequences 1–2; smallest key `a`@2 (PUT), largest key `c`@1 (PUT).
An internal key is the user key then `fixed64LE(sequence << 8 | type)`, `PUT = 1` (ADR-0004).

Payload (27 bytes):

```
07                        tag 7: new file
00                        level 0
0C                        number 12
D2 09                     size 1234
01                        smallest sequence 1
02                        largest sequence 2
09                        smallest key length 9
61 01 02 00 00 00 00 00   "a", trailer (2 << 8 | 1)
00
09                        largest key length 9
63 01 01 00 00 00 00 00   "c", trailer (1 << 8 | 1)
00
```

The whole file (54 bytes):

```
4E 41 4D 65 6C 61 68 53   magic "ShaleMAN"
02 00 00 00               FORMAT_VERSION = 2
00 00 00 00               reserved
41 03 B2 E9               payloadCrc = crc32c(01 ‖ payload)
1B 00                     length = 27
01                        type = FULL
B7 1F A8 42               headerCrc = crc32c(41 03 B2 E9 1B 00 01)
07 00 0C D2 09 01 02 09   payload ...
61 01 02 00 00 00 00 00
00 09 63 01 01 00 00 00
00 00 00
```

Both CRCs, and the golden file `golden/manifest/v2/three-edits.manifest`, were computed by an
implementation independent of Shale's before the encoder existed. `GoldenManifestTest` decodes the
golden and re-encodes it byte for byte.

## 6. Version history

| Version | Milestone | Status | Change |
|---|---|---|---|
| 2 | M5 | current | the first manifest (ADR-0012) |

**Why it starts at 2.** The file header's version *is* the log framing version, and it selects the
fragment-header layout. A "version 1" manifest would be read with v1's 7-byte, unchecksummed
fragment headers — the layout ADR-0024 replaced because a damaged `length` could pass for a torn
tail. So the manifest is born on framing v2, and its version says so.
