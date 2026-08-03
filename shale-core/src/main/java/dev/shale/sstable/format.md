# SSTable on-disk format

Immutable sorted table, LevelDB-style (ADR-0010). All fixed-width integers little-endian
(ADR-0005); lengths are varints (LevelDB varint32/64, `dev.shale.internal.coding.Varints`).
Files are named `NNNNNN.sst`. Keys stored are encoded internal keys (ADR-0004), ordered by
`InternalKeyComparator` (user key ascending, sequence descending).

- **Magic:** `0x5368616C65535354` ("ShaleSST"), `fixed64LE`, at the end of the file.
- **FORMAT_VERSION:** `1`.
- **Checksum:** CRC32C (Castagnoli, `java.util.zip.CRC32C`), unmasked, over each block's
  `content ‖ type` byte.
- **Restart interval:** 16 keys.

---

## 1. File layout

A file is a sequence of blocks followed by a fixed footer:

```
[data block 0]
[data block 1]
   ...
[data block N-1]
[metaindex block]     (empty at v1; the M7 filter-block handle will live here)
[index block]
[footer]              (fixed 52 bytes, at end of file)
```

Every block — data, metaindex, index — has the identical block encoding (§2) and is followed
by a 5-byte block trailer (§3). A `BlockHandle` (§4) locates a block by `{offset, size}`, where
`size` is the block's content length and excludes the trailer.

## 2. Block content

```
[entry 0][entry 1] ... [entry K-1]          restart-delimited, prefix-compressed entries
[restart[0] fixed32] ... [restart[R-1] fixed32]   byte offset of each restart entry
[num_restarts fixed32]                      R
```

Each **entry** is prefix-compressed against the previous entry's key:

| Field       | Type     | Notes                                                       |
|-------------|----------|-------------------------------------------------------------|
| shared      | varint32 | bytes of key shared with the previous entry (0 at a restart)|
| non_shared  | varint32 | bytes of key that follow the shared prefix                  |
| value_len   | varint32 | value byte count                                            |
| key_delta   | bytes    | `non_shared` bytes; the full key is `prev[:shared] ‖ key_delta` |
| value       | bytes    | `value_len` bytes                                           |

At a **restart point** (every 16th entry, and the first) `shared = 0`, so the full key is
present and a binary search can start there. `num_restarts` and the restart array let a reader
binary-search the restarts, then scan forward at most 16 entries.

## 3. Block trailer (5 bytes, after every block's content)

| Offset | Size | Type      | Field  | Notes                                             |
|--------|------|-----------|--------|---------------------------------------------------|
| 0      | 1    | uint8     | type   | compression: `0x00` none (only value at v1)       |
| 1      | 4    | fixed32LE | crc32c | CRC32C over the block `content ‖ type` byte        |

The CRC is verified before the block's contents are interpreted; a mismatch is
`CorruptionException` with the file offset (N4). `type` is validated (`0x00` at v1); a reserved
value is corruption.

## 4. BlockHandle

A locator, stored wherever one block points at another (index entries, footer):

| Field  | Type     | Notes                                            |
|--------|----------|--------------------------------------------------|
| offset | varint64 | byte offset of the block's content in the file   |
| size   | varint64 | block content length (excludes the 5-byte trailer)|

## 5. Index block and metaindex block

The **index block** is a normal block (§2) with one entry per data block: the key is the data
block's **last internal key** (a valid separator — every key in the block is ≤ it, and it is <
the next block's first key), and the value is the `BlockHandle` (§4) of that data block. A point
lookup finds the first index entry whose key ≥ the target, then searches that one data block.

The **metaindex block** is a normal block that maps a metadata name → `BlockHandle`. At v1 it has
**zero entries**; at M7 it will carry `"filter.<name>" → filterBlockHandle`.

## 6. Footer (fixed 52 bytes, at end of file)

| Offset | Size | Type      | Field             | Notes                                    |
|--------|------|-----------|-------------------|------------------------------------------|
| 0      | var  | BlockHandle | metaindex handle | offset+size varints                      |
| var    | var  | BlockHandle | index handle     | offset+size varints                      |
| var    | pad  | zeros     | padding to 40     | fills the two handles' area to 40 bytes  |
| 40     | 4    | fixed32LE | FORMAT_VERSION    | `1`; rejected if unknown                  |
| 44     | 8    | fixed64LE | magic             | `0x5368616C65535354` ("ShaleSST")         |

The footer is read from the end: the last 8 bytes are the magic (a mismatch means "not an
SSTable"), the 4 before it the version, and the preceding 40 hold the two zero-padded handles.
Padding bytes are written zero and verified zero on read (on-disk-formats.md §2).

## 7. Worked example

The committed golden file `two-puts.sst` (132 bytes) — two puts, `("a", "1", seq 1)` and
`("b", "22", seq 2)`. Internal keys are `61 ‖ 0101000000000000` and `62 ‖ 0102000000000000`
(`userKey ‖ fixed64LE((seq<<8)|PUT)`), 9 bytes each. Only the first entry of each block is a restart
(interval 16), and the user keys `a`/`b` differ at byte 0, so entry 1 shares a 0-length prefix.

```
DATA BLOCK  content [0, 35), handle {offset 0, size 35}
  entry 0 (restart):  00 09 01  61 01 01 00 00 00 00 00 00  31
                      │  │  │   └ internalKey "a" (seq 1)    └ value "1"
                      │  │  └ value_len = 1
                      │  └ non_shared = 9
                      └ shared = 0
  entry 1:            00 09 02  62 01 02 00 00 00 00 00 00  32 32
                      shared=0 non_shared=9 value_len=2  internalKey "b" (seq 2)  value "22"
  restarts:           00 00 00 00           restart[0] = offset 0
  num_restarts:       01 00 00 00           R = 1
  trailer:            00  49 a6 2d a2        type=none, crc32c over content ‖ type   [golden-pinned]

METAINDEX BLOCK  content [40, 48), handle {offset 40, size 8}  — empty
  restarts+count:     00 00 00 00  01 00 00 00
  trailer:            00  0f 07 f4 83

INDEX BLOCK  content [53, 75), handle {offset 53, size 22}
  entry 0 (restart):  00 09 02  62 01 02 00 00 00 00 00 00  00 23
                      key = data block's last key "b"        value = BlockHandle{offset 0, size 0x23}
  restarts+count:     00 00 00 00  01 00 00 00
  trailer:            00  02 e8 4f 44

FOOTER  [80, 132), 52 bytes
  metaindex handle:   28 08                 {offset 40, size 8}
  index handle:       35 16                 {offset 53, size 22}
  padding:            00 … 00               zero to offset 40
  FORMAT_VERSION:     01 00 00 00           = 1
  magic:              54 53 53 65 6c 61 68 53   0x5368616C65535354 ("ShaleSST")
```

The CRCs and offsets above are pinned by the committed golden file
`shale-core/src/test/resources/golden/sstable/v1/two-puts.sst` and its `.json` sibling; the golden
test decodes it and asserts the logical contents, and a bit-flip-at-every-offset test asserts every
corruption is detected or provably harmless (on-disk-formats.md §3, §4).

## 8. Rationale

See ADR-0010. Block granularity gives a corruption boundary (per-block CRC, N4), a future home for
the block cache and bloom filter, and prefix compression + restart points — the techniques M3 exists
to teach. The metaindex block and the reserved compression-type byte make the M7 filter and a future
codec additive rather than layout changes.

## 9. Version history

| Version | Milestone | Status  | Change                                             |
|---------|-----------|---------|----------------------------------------------------|
| 1       | M3        | current | Initial block table: data/metaindex/index + footer |
