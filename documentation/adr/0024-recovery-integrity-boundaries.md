# 0024. Protect the log framing, and let recovery decide only on verified bytes

- **Status:** Accepted
- **Date:** 2026-10-10
- **Milestone:** M4.5
- **Reversible:** no — it adds a header checksum to every log fragment (WAL v2, and the manifest
  that M5 builds on the same framing). Old v1 segments stay readable; a v2 segment cannot be
  read by an M4 build.

## Context

An external engineering review of `main` at `c66adea` (2026-10-08/09, five rounds) reproduced two
distinct one-bit changes to a WAL fragment header that make `Shale.open` succeed with **every
acknowledged `SYNC` write missing**, and then delete the WAL that held them:

| Id | The bit | What the reader did |
|---|---|---|
| SH-08 | type `FULL` (1) → `ZERO` (0) | skipped the rest of the block as padding, before checking any CRC |
| SH-18 | one bit of `length` (16 → 16,400) | saw a payload running past the end of the file, called it a torn tail, and stopped — before the CRC could be checked |

Both bits sit in fields the fragment CRC does not cover (ADR-0007 covers `type ‖ payload`; the
length is not covered, and the ZERO branch runs before the CRC). Fixing SH-08 alone leaves SH-18.
Adding the length to the existing payload CRC would not fix SH-18 either: when the claimed payload
is not in the file, the reader cannot compute that CRC at all.

The root error is a **classification decision made on unverified bytes**. Recovery has to tell a
*torn tail* (a crash mid-append: bytes never written, never acknowledged, safe to drop) from
*corruption* (bytes written and then damaged: never repaired silently, N4). ADR-0007 assumed "a
missing byte is a torn tail, a present-but-wrong byte is corruption". But in v1 the reader learns
*whether* a byte is missing from the length field, and the length field can itself be wrong.
`on-disk-formats.md` §2 already says never to trust a length before its checksum has passed. v1
breaks that rule exactly where it matters most.

The same review found three more decoders that trust fields before validating them in context:
- SH-11: a 10-byte varint whose last byte exceeds the one bit left decodes as 0;
- SH-13: a WAL key length of 2^32 + 9 narrows to 9;
- SH-14, SH-15, SH-16: SSTable restart counts, footer handle roles, and handle sizes.

None of these needs a format change. All of them need one stated contract.

Three forces decide this:
- **Correctness first** (charter §4): "no acknowledged write is ever lost" is the project's
  durability claim. A recovery that silently certifies an empty database falsifies it.
- **The manifest inherits the framing.** M5 reuses the WAL's block framing for `MANIFEST-*`
  (M5 plan §2.2). A manifest that misreads a damaged length as a torn tail drops file edits, and
  that decides which tables exist. The framing must be fixed *before* the manifest is born.
- **Availability for genuine crashes.** A `kill -9` mid-append leaves a header with a short
  payload. Recovery must keep accepting that without an operator, or D5's `kill -9` demonstration
  becomes "and then a human truncates a file".

## Options considered

### Option A — keep v1, narrow the documented guarantee
Document that a damaged length or type can be read as a torn tail. Cheapest. But the engine
still deletes acknowledged data on a one-bit fault, and the manifest inherits the defect.
Rejected as a fix. It is still adopted as the *wording* for v1 segments that already exist.

### Option B — make `STRICT` the engine's policy
Rejects both fixtures. It also rejects every genuine torn append, so every crash during a write
needs an operator. That trades a silent-loss bug for an availability bug, and still cannot tell
the two apart. Rejected.

### Option C — v1 only, conservative: refuse any tail v1 cannot verify
Fix the ZERO branch and the decoders. Treat "a complete header whose payload runs past the end of
the file" as *unverifiable* rather than torn: refuse to open and leave every input untouched.
This closes the silent loss. It costs the same availability as Option B for the most common crash
shape, a header written and its payload cut short. That shape is permanent, because nothing in v1
can ever verify that header.

### Option D — protect the header (v2), and keep C's conservative rule only for old v1 segments
Add a 4-byte CRC32C over the 7 v1 header bytes, so the fragment header is
`payloadCrc ‖ length ‖ type ‖ headerCrc`. The reader verifies the header checksum **before** it
uses `length` or `type` for anything, including deciding that a tail is torn. A verified header
with a short payload is then a genuine torn append; a header that fails its checksum is
corruption. Old v1 segments, which exist only in directories written before this change, are
read under Option C's rule.

Prior art: SQLite's WAL frame header carries its own checksums and salts, verified before the
frame is trusted (SQLite file format §4). PostgreSQL's WAL record header (`XLogRecord.xl_crc`)
covers the header together with the data. RocksDB's "recyclable" log records add a log number to
the header, for a related reason: telling a stale tail from a real one.

### Option E — one CRC over `length ‖ type ‖ payload`
Fixes SH-08, and catches a length change *inside* the file. It does not fix SH-18: when the
claimed payload runs past the end of the file, the CRC cannot be computed. Rejected.

## Decision

**Option D.** In full:

1. **Log framing v2** (file `FORMAT_VERSION` 2; magic, 16-byte file header, 32 KiB blocks and
   fragment types unchanged). The fragment header is 11 bytes:

   | Offset | Size | Field | Notes |
   |---|---|---|---|
   | 0 | 4 | `payloadCrc` fixed32LE | CRC32C over `type ‖ payload`, as in v1 |
   | 4 | 2 | `length` fixed16LE | payload bytes, ≤ 32768 − 11 |
   | 6 | 1 | `type` | `1 FULL · 2 FIRST · 3 MIDDLE · 4 LAST`; `0` is never written |
   | 7 | 4 | `headerCrc` fixed32LE | CRC32C over bytes 0–6 of this header |

   - When fewer than 11 bytes remain in a block, they are zero padding. The reader verifies they
     are zero.
   - The framing lives in `dev.shale.internal.log` (`LogWriter`, `LogReader`). The WAL uses it now
     and the M5 manifest is born on it, so the two logs share one reader and one set of tests.

2. **v2 tail classification.** The reader stops at the first condition below that applies, in
   this order:

   | Condition at the reader's position | Classification |
   |---|---|
   | Fewer than 11 bytes left in the file | **torn header** — torn tail |
   | Every byte from here to the end of the file is zero | **unwritten tail** — torn tail (a filesystem zero-fills space allocated but never written) |
   | `headerCrc` mismatch | **corruption** |
   | Verified header: type not 1–4, or length over the block's remaining space | corruption (the writer cannot produce it) |
   | Verified header, payload runs past the end of the file | **torn payload** — torn tail |
   | Verified header, `payloadCrc` mismatch | corruption |
   | Fragment out of sequence (`MIDDLE` with no `FIRST`, …) | corruption |
   | End of file inside an open `FIRST…` record | torn tail |
   | Block padding not zero | corruption |

   - A torn tail is accepted (`TRUNCATE_TAIL`) **only in the newest segment that recovery
     replays**, and only after its last complete record.
   - A torn tail in an older segment is corruption. This is sound because a segment is now synced
     when it is closed at a memtable switch (item 5), so a crash cannot leave an older segment
     incomplete.
   - A file of 1–15 bytes (the file header itself torn) is a torn tail when it is the newest
     segment. This replaces the M5 plan's Step 4.2.

3. **v1 segments: compatible repairs plus the conservative rule (Option C).**
   - `ZERO` is accepted only when every byte from the header to the end of the file is zero (an
     unwritten tail); anywhere else it is corruption.
   - Block padding is verified zero.
   - **A complete v1 header whose payload runs past the end of the file is *unverifiable*.** The
     reader throws `CorruptionException("unverifiable WAL tail", file, offset, claimed length,
     bytes present)` under both policies, because v1 cannot tell a torn append from a damaged
     length.
   - A torn v1 *header* (fewer than 7 bytes) remains a torn tail, because no unverified field is
     used to decide it.
   - The operator's way out is explicit. The exception names the file and the fragment offset; if
     the operator accepts losing that tail, they truncate the segment to that offset and reopen.
     There is no automatic salvage and no recovery tool.

4. **Rejected recovery leaves the directory as it was.** If `open` throws, no WAL segment has been
   deleted or rewritten and no new table or segment remains. Only `*.tmp` files, which are never
   inputs, may have been removed. An input whose recovery is unresolved is never deleted as a side
   effect of an open that looks successful. M5 extends this through the manifest: inputs are
   deleted only after the recovered state is published (M5 plan §2.3 steps 9–11).

5. **A WAL segment is synced when it is closed at a switch.** That costs one fsync per memtable
   (4 MiB by default). In return, recovery can rely on every segment but the newest being complete,
   which is what item 2's newest-segment rule rests on. It also closes a prefix violation: without
   it, a power loss after a switch could lose `NONE` writes in the old segment while `SYNC` writes
   in the new one survive.

6. **The decoder contract** (WAL record codec, varints, SSTable blocks, handles, footer). It holds
   for every format, current and future:
   - an integer is decoded wide, then checked against the format's unsigned width (a varint64's
     tenth byte carries at most one bit; a varint32 field rejects values ≥ 2^32);
   - a length is checked against the remaining extent *by subtraction*, before it is narrowed,
     allocated, or added to an offset;
   - a record is consumed exactly: trailing bytes are corruption unless the format version
     defines them;
   - structural fields are validated before use. For SSTables: the restart count is bounded by
     the block length before any multiplication; restart offsets lie inside the entry region,
     strictly increase, and start entries with `shared = 0`; and the v1 file layout holds —
     data blocks lie wholly before the metaindex, the metaindex wholly before the index, and the
     index's trailer ends exactly where the footer begins;
   - each known malformation throws `CorruptionException` with field, file, offset, expected and
     actual values. There is **no catch-all** that renames an unexpected `RuntimeException` as
     corruption; an unexpected exception stays a visible bug.

7. **Guarantees are stated per failure model.** Every claim in the docs names which of these it
   covers, and a result in one never stands in for another:

   | Model | What it means here | How it is covered |
   |---|---|---|
   | Clean restart | `close()` then `open` | model test |
   | Malformed input | bytes changed after they were written | bit-flip at every offset (every bit for log headers) plus malformed-structure fixtures |
   | Torn tail | the file ends mid-append | truncation at every offset |
   | Process death | `kill -9`; the page cache survives | truncation (M4.5); a child-JVM kill test (D5) |
   | Simulated power loss | unsynced data and unsynced directory entries vanish | `FaultInjectionEnv` (M5) |
   | Physical power loss | a real machine loses power | **not tested**; argued from fsync ordering only |

## Rationale

- **Option D restores the rule that recovery decisions rest only on verified bytes.** The only
  difference between a torn append and a damaged length is whether the length was written as it
  reads. A checksum over the header is the smallest thing that answers that question, and the
  question has to be answered before the length is used. That is the whole lesson of SH-18, and
  it is the more instructive implementation: it turns a reader that *mostly* works into one whose
  every branch can be argued.
- **The cost is small and exact.** Four bytes per fragment:
  - a 30-byte record grows from 37 to 41 bytes (about 11%);
  - a 32 KiB fragment grows by 0.01%;
  - one CRC32C over 7 bytes is noise beside the payload CRC and the fsync.

  Write throughput is fsync-bound under `SYNC`. M5.5's baseline measures the engine as it is
  after this change, so no speed claim is made for or against it here.
- **Timing.** Doing it now, before M5, means the manifest never has a v1, and M7's batch-record
  change becomes WAL v3 on the same framing instead of a second framing migration.
- **Why also Option C for v1.** Old segments exist only in directories written before M4.5, are
  replayed once, and are then deleted. Refusing their unverifiable tails costs availability
  exactly once per crashed pre-M4.5 directory. Accepting those tails is how SH-18 lost data.

## Consequences

**Positive:**
- Both reproduced one-bit faults, and every other single-bit change to a v2 fragment header, are
  detected rather than read as torn.
- A genuine torn append is still recovered without an operator.
- The manifest inherits a framing whose tail rule can be argued.
- One decoder contract replaces case-by-case bounds checks.

**Negative:**
- A second framing version to read forever.
- Four bytes per fragment.
- One extra fsync per memtable switch.
- A crashed pre-M4.5 database whose last v1 fragment has a complete header and a short payload
  will not open without an explicit truncation. This is the stated availability cost of not
  trusting v1.
- A power-loss tail of non-zero garbage (not zeros, not a byte-prefix of what was written) is
  reported as corruption. Journaling filesystems in their default modes (ext4 `data=ordered`,
  APFS) do not expose such tails; on one that does, the operator decides, as N4 requires.

**Neutral:**
- CRC32C detects accidental damage. It is not authentication, and nothing here defends against a
  deliberate forger.
- SSTable bytes do not change. The new structural checks describe what the v1 writer has always
  produced.

**If we need to reverse this:** v2 segments are drained like any WAL: a build that reads v2
replays them and writes the next format. Dropping the v1 conservative rule would bring SH-18
back for old segments; doing that needs a new ADR.

## References

- External engineering review of Shale, rounds 1–5 (2026-10-08/09): findings SH-08, SH-11,
  SH-13–SH-16, SH-18. The open-defect register in [bug-log.md](../bug-log.md) keeps their evidence.
- SQLite, "Database File Format" §4, the WAL frame header and its checksums
  (sqlite.org/fileformat2.html).
- PostgreSQL `src/include/access/xlogrecord.h` (`xl_crc`).
- LevelDB `db/log_reader.cc` (`kZeroType` accepted only with length 0, for preallocated regions);
  RocksDB `db/log_format.h` (recyclable record types).
- Pillai et al., "All File Systems Are Not Created Equal" (OSDI 2014): what appended data can
  look like after a crash.
- `documentation/conventions/on-disk-formats.md` §2; [[0007-wal-block-log-format]] (amended here),
  [[0008-durability-and-clock]], [[0010-sstable-block-table-format]].
