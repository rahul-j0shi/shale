# M4.5 — Integrity boundaries: implementation plan

**Status:** next to implement. It was inserted before M5 on 2026-10-10, in response to the
October 2026 engineering review. Baseline green on JDK 25 at `c66adea` (161 tests).
**Depends on:** M4. **ADR:** [0024](../adr/0024-recovery-integrity-boundaries.md), already
`Accepted`; this plan implements it. **Next:** [M5](m5-manifest-and-recovery.md).
**Estimate:** 2–3 focused weeks, in 8 steps (Steps 0–7).

**Goal.** Recovery and every decoder decide only on bytes they have verified.
- Today two different one-bit changes to a WAL fragment header make `open` succeed with every
  acknowledged write missing, and then delete the WAL (SH-08, SH-18).
- Four decoders trust a field before checking it in context (SH-11, SH-13 to SH-16).

M4.5 makes each of these fail loudly, without touching the inputs. It puts a checksum on the log
header, so a genuine torn append is still recovered without an operator. It comes before M5
because M5's manifest reuses the WAL framing (M5 plan §2.2): fixing the framing first means the
manifest never inherits the defect.

**The deliverable a reader can check** is one case study, built from the tests this milestone
adds: *two different header faults → the invariant they break → why the tempting patch was not
enough → the format decision → regressions and controls → the limits that remain.* It is the
`architecture/m4-5-integrity-boundaries.md` written in Step 7.

---

## 1. Where the code is today (the starting point)

| Fact | Where |
|---|---|
| A fragment with type `ZERO` skips the rest of its block before any CRC check, whatever its length or position | `WalReader.parse` (`WalReader.java:79-82`) |
| A complete header whose claimed payload runs past the end of the file is a torn tail, decided from the unchecked `length` | `WalReader.parse` (`WalReader.java:87-91`) |
| Block padding (< 7 bytes) is skipped without checking that it is zero | `WalReader.java:69-72` |
| `open` replays with `TRUNCATE_TAIL`, flushes what it recovered, then deletes every segment | `Shale.open` (`Shale.java:160-179`) |
| A segment closed at a memtable switch is not synced | `Shale.switchAndFlush` → `WalWriter.close` (`WalWriter.java:120`) |
| A 10-byte varint is accepted whatever its last byte holds; `[0x80 ×9, 0x02]` decodes as 0 | `Varints.get` (`Varints.java:46-60`) |
| WAL key and value lengths are narrowed with `(int)` before any range check; trailing bytes after the value are ignored | `WalRecordCodec.decode` (`WalRecordCodec.java:39-50`) |
| The restart-array offset is computed with `int` multiplication before the count is bounded | `Block` constructor (`Block.java:36-41`) |
| Restart offsets are never checked for order, range or `shared == 0` | `Block.Iterator.seekToRestart`, `firstKeyAtRestart` |
| A handle size goes through `Math.toIntExact` (it throws `ArithmeticException`), and `offset + frameSize` can overflow | `SSTableReader.readBlock` (`SSTableReader.java:147-155`) |
| The footer's two handles are checked for magic, version and padding, but not for role, order or overlap | `Footer.decode` |
| The WAL has no bit-flip test at all (`wal/format.md` §5 says it has one); the SSTable test flips each byte with XOR `0xFF` | `wal/WalTest`, `sstable/SSTableTest.flippingAnyByte_isDetectedOrHarmless` |

## 2. The design (decided — ADR-0024 records it, with the alternatives)

### 2.1 Log framing v2 (`dev.shale.internal.log`)

- **The fragment header grows from 7 to 11 bytes:** `payloadCrc(4) ‖ length(2) ‖ type(1) ‖
  headerCrc(4)`. The first 7 bytes are exactly v1's. `headerCrc` is CRC32C over them.
- **Unchanged:** the file header (magic, `FORMAT_VERSION` = 2, 4 reserved zero bytes), the 32 KiB
  blocks, the fragment types and the payload CRC.
- **Padding:** when fewer than 11 bytes remain in a block, they are zero and the reader verifies
  that. The largest v2 payload in one fragment is 32768 − 11 = 32757 bytes.
- **Code:** `LogWriter` and `LogReader` hold the framing, with the magic and version passed in.
  `WalWriter` and `WalReader` delegate to them. M5's manifest uses them with its own magic, so
  this replaces M5 plan Step 5.2.
- **Format docs:** a new `internal/log/format.md` holds the framing, v1 and v2, with a worked hex
  example. `wal/format.md` keeps the WAL file header and record payload, and points to it.

### 2.2 Tail classification

- **v2** follows ADR-0024 §2 exactly: the first matching row decides.
- **v1** follows ADR-0024 §3:
  - `ZERO` is accepted only when everything from the header to the end of the file is zero;
  - padding is verified;
  - a complete header with a short payload is `CorruptionException("unverifiable WAL tail", …)`
    under **both** policies;
  - a torn header (fewer than 7 bytes) stays a torn tail.
- **`LogReader.read` returns a `LogReadResult`:**
  - `records`: the complete payloads, in order;
  - `endOffset`: the offset just past the last complete record;
  - `tail`: one of `CLEAN`, `TORN_HEADER`, `UNWRITTEN`, `TORN_PAYLOAD`, `OPEN_RECORD`.

  Corruption, and an unverifiable v1 tail, throw instead of returning. Under `STRICT`, any tail
  other than `CLEAN` throws.
- **`RecoveryPolicy` is unchanged.** Its Javadoc states the v1 exception.

### 2.3 Recovery in the engine

1. **Newest segment only.** `Shale.open` replays segments in order. Only the newest may end in a
   torn tail. Every older one must read `CLEAN`, or `open` throws `CorruptionException` naming
   the segment.
   - A newest segment of 0 bytes is empty.
   - A newest segment of 1–15 bytes is a torn file header, so it is empty too. Today this throws
     even under `TRUNCATE_TAIL`; M5 plan Step 4.2 moves here.
   - A 1–15-byte older segment is corruption.
2. **Sync at switch.** `WalWriter.close()` forces the channel before closing it, under the
   comment `// DURABILITY: a closed segment is complete, so only the newest can be torn
   (ADR-0024 §5)`.
3. **A rejected open changes nothing.** Every throw in `open` happens before the recovery flush
   writes a table and before any segment is deleted. That is today's order (`Shale.java:160-179`),
   and M4.5 pins it with a test. The only files `open` may have removed are `*.tmp`.
4. **The operator's way out for v1.** The exception message is `unverifiable WAL tail in
   000001.wal at offset 16: header claims 16400 payload bytes, 39 present; v1 cannot tell a torn
   append from a damaged length — to accept losing this tail, truncate the file to 16 bytes and
   reopen`. Both numbers come from the reader. No API or tool is added.

### 2.4 The decoder contract (ADR-0024 §6)

| Decoder | Rule added |
|---|---|
| `Varints.get` | The tenth byte must be 0 or 1. A larger value is `CorruptionException("varint overflows 64 bits")`. |
| `Varints.getUnsigned32` (new) | Decodes like `get`, but rejects values ≥ 2^32. Every field the formats call `varint32` uses it. |
| `WalRecordCodec.decode` | Each length is checked with `length <= payload.length - start` while still a `long`, then narrowed. After the value, `start + valueLength` must equal `payload.length`; trailing bytes are corruption. |
| `Block` | `restartCount` must lie in `[0, (length − 4) / 4]` before any multiplication. Restart offsets must lie in `[0, restartArrayOffset)`, strictly increase, start at 0, and point at entries with `shared == 0`. A block with `restartCount == 0` must have an empty entry region: that is the writer's empty block. |
| `BlockHandle`, `SSTableReader.readBlock` | `offset ∈ [0, fileSize]`; `size ∈ [0, fileSize − offset − 5]`, computed by subtraction on `long`s, so no `toIntExact` can throw. |
| `SSTableReader.open` (v1 layout) | Let *F* = file size − 52, where the footer starts. Then `index.offset + index.size + 5 == F`; `metaindex.offset + metaindex.size + 5 == index.offset`; and every data handle in the index ends at or before `metaindex.offset`, in increasing offset order. |
| everywhere | Each known malformation throws `CorruptionException` with field, offset, expected and actual. **No `catch (RuntimeException)`** wraps a decoder. |

## 3. New and changed types

| Type | Package | Visibility | Step |
|---|---|---|---|
| `LogWriter`, `LogReader`, `LogReadResult`, `LogFormat` (v1 and v2 framing constants) | `dev.shale.internal.log` | public (internal) | 4 |
| `WalWriter` | `dev.shale.wal` | writes v2 segments; `close()` syncs; delegates to `LogWriter` | 4, 5 |
| `WalReader` | `dev.shale.wal` | unchanged `readAll` signature; dispatches on the header version; delegates to `LogReader` | 4 |
| `WalFormat` | `dev.shale.wal` | `FORMAT_VERSION` 2, with `READABLE_VERSIONS = {1, 2}` | 4 |
| `RecordType` | `dev.shale.wal` | `ZERO` stays for v1 decoding only; v2 rejects it | 3, 4 |
| `Varints` | `dev.shale.internal.coding` | adds `getUnsigned32`; `get` bounds the tenth byte | 3 |
| `WalRecordCodec` | `dev.shale.wal` | contextual lengths; exact consumption | 3 |
| `Block`, `BlockHandle`, `Footer`, `SSTableReader` | `dev.shale.sstable` | structural validation (§2.4); no API change | 6 |
| `Shale` | `dev.shale` | newest-segment rule; nothing else visible | 5 |

## 4. Steps

Each step is one branch off `main`, merged green before the next starts. `Milestone: M4.5` on
every `feat`/`fix`. **Every fix commit contains the regression that fails without it.** One patch
may close several findings, but each finding keeps its own named test, so no fix can quietly
stand in for another.

### Step 0 — a bootstrap for every supported platform (`build/bootstrap-platforms`), ~½ day
The guides promise Linux and macOS, but `scripts/bootstrap.sh` fetches a JDK for Linux x64 only.
A newcomer on a Mac cannot follow the README's first command.
1. `build(build)`: `bootstrap.sh` detects the OS and architecture (`uname -s`, `uname -m`) and
   picks the pinned Temurin JDK 25 archive for `linux/x64`, `linux/aarch64`, `mac/x64` or
   `mac/aarch64`. Each archive has its own pinned SHA-256, checked with `sha256sum` or
   `shasum -a 256`, whichever exists. On macOS the JDK home is `Contents/Home` inside the archive.
   Any other platform fails with a message naming the manual route (install JDK 25, set
   `JAVA_HOME`).
2. `build(ci)`: the workflow runs `bootstrap.sh` then `build` on `ubuntu-latest` and
   `macos-latest`.
3. `docs(docs)`: remove the "Linux x64 only" notes from the README, `CONTRIBUTING.md` and the
   guide's §1.

**Done when:** CI is green on both runners from a clean checkout.

### Step 1 — correct the claims in the code (`docs/m04-5-javadoc-claims`), ~½ day
The markdown was corrected with this plan; the Javadoc must say the same.
1. `docs(api)`:
   - **`Durability`.** `SYNC` forces the record. It is power-loss durable only once the segment's
     directory entry is synced as well (M5). Process-crash durability is unconditional.
   - **`Shale.scan` and `Cursor`.** A cursor is *weakly consistent* (SH-03): it never sees a
     partial record, but it may or may not see a write made after it was opened. Sequence
     snapshots arrive at M7.
   - **`Shale.put`/`delete`.** A `StorageException` thrown by a flush the write triggered does
     not mean the write is absent: it was logged and applied first. Treat its outcome as unknown
     and reopen.
   - **`WalReader` and `RecoveryPolicy`.** What a torn tail is, and the known v1 gap, linking
     ADR-0024 until Step 5 closes it.
   - **`Shale`'s class Javadoc.** Background flush arrives at M5.5, not M6.

**Done when:** `build` is green; no Javadoc claims a snapshot or unconditional power loss.

### Step 2 — capture the v1 fixtures (`m04-5/v1-fixtures`), ~1 day
v1 goldens must be written by the v1 writer, so they are captured **before** Step 4 changes it.
1. `test(wal)`: `WalFixtureGenerator`, a test-source `main` run once per format version with the
   then-current writer, writes these from the contents defined in `WalFixtures`:
   - `golden/wal/v1/two-puts.wal`: `A`→`ack-A`, `B`→`ack-B`, `SYNC`, sequences 1–2. This is the
     review's fixture: 62 bytes, the first fragment header at offset 16, its length at 20–21, its
     type at 22.
   - `golden/wal/v1/fragmented.wal`: one 80,000-byte value (`FIRST`, `MIDDLE`, `LAST` across
     three blocks), then one small put.
   - `golden/wal/v1/block-padding.wal`: records sized so that blocks 0 and 1 end with 3 and 6
     padding bytes.

   Each has a `.json` of its logical contents and record end offsets.
2. `test(wal)`: `GoldenWalTest` decodes each fixture against its `.json`.

**Done when:** the fixtures are committed and read back. The commit body records the generator's
commit SHA. The generator is never run again (`on-disk-formats.md` §4).

### Step 3 — compatible decoder repairs (`m04-5/decoder-repairs`), ~3 days
One `fix` commit per row. No format change.

| Commit | Closes | Failing test first, plus its controls |
|---|---|---|
| `fix(coding)`: bound the tenth varint byte | SH-11 | `VarintsTest`: `[0x80 ×9, 0x02]` and `[0xFF ×9, 0x7F]` throw; `0xFFFF_FFFF_FFFF_FFFF` (tenth byte `0x01`) round-trips; truncated and unterminated runs still throw. The round-trip property stays green. |
| `fix(coding)`: add `getUnsigned32` | — | `VarintsTest`: 2^32 − 1 decodes; 2^32 throws |
| `fix(wal)`: validate WAL record lengths in context | SH-13 | `WalRecordCodecTest`: key length 2^32 + 9 with 9 key bytes throws (the review's fixture); 2^31 and 2^32 − 1 throw for overrun, before any allocation; a length equal to the remaining bytes decodes; one trailing byte after the value throws; the golden record still decodes. |
| `fix(wal)`: accept ZERO only as an unwritten tail; verify padding | SH-08 | `WalTest`: on a copy of `two-puts.wal`, bit 0 of byte 22 flipped (`FULL` → `ZERO`) throws under both policies. Controls: `two-puts.wal` plus 7, 100 and 40,000 zero bytes reads 2 records under `TRUNCATE_TAIL`; a non-zero padding byte in `block-padding.wal` throws; the unmodified `block-padding.wal` reads. |

**Done when:** each regression fails before its fix and passes after, and every existing test is
green.

### Step 4 — log framing v2 (`m04-5/log-framing-v2`), ~5 days
Format first (`on-disk-formats.md` §3).
1. `docs(wal)`: `internal/log/format.md` gives the v1 and v2 fragment headers, the §2.2 tail
   table for each, and a worked v2 hex example with its CRCs. The CRCs are pinned once the golden
   exists. `wal/format.md` gets the v2 header row and its version history: v1 *readable*, v2
   *current*.
2. `refactor(wal)`: extract the v1 framing into `LogWriter`/`LogReader`; `WalWriter` and
   `WalReader` delegate. `GoldenWalTest` stays green on all three v1 fixtures, which proves no byte
   changed.
3. `feat(wal)`: v2 framing, written by `WalWriter` and dispatched on by `WalReader`. Commit with
   `Format-Change: wal v2 — 4-byte header CRC per fragment` and `Reversible: no`.
4. `test(wal)`:
   - `golden/wal/v2/two-puts.wal`, `fragmented.wal`, `block-padding.wal`, written by the new
     writer, each with its `.json`. v2 padding cases: 3 and 10 bytes.
   - `LogFramingPropertyTest`: a round trip over generated record sizes 0–100,000, written at
     every block position.
   - `LogV2BitFlipTest`:
     - every **bit** of every fragment header, and every **byte** (XOR `0x01`, then `0xFF`)
       everywhere else, in each v2 golden, throws `CorruptionException`;
     - none is ever classified torn and none is harmless, because v2 has no unchecked byte;
     - the expected type is asserted exactly, so an `ArrayIndexOutOfBoundsException` fails the
       test.
   - `LogV2TruncationTest`: truncating each v2 golden at every offset yields exactly the records
     that end at or before it under `TRUNCATE_TAIL`, and throws under `STRICT` unless the offset
     is a record boundary.
   - `LogV2HeaderMatrixTest`, the review's finite matrix, run on every fragment header of each v2
     golden:
     - each of the 88 single-bit header flips;
     - each truncation inside that header and inside its payload;
     - a zero-filled tail after it.

     Each case asserts its §2.2 row by name.
   - The SH-18 fixture on v2: `two-puts.wal` (v2) with bit 6 of byte 21 flipped throws
     `CorruptionException("fragment header CRC mismatch")`. Under v1 the same mutation was
     silently read as torn.

**Done when:** all of the above pass, `ShaleCrashTest` is green (it now exercises v2 segments),
and the format docs match the golden bytes.

### Step 5 — the recovery boundary in the engine (`m04-5/recovery-boundary`), ~3 days
1. `fix(wal)`: the v1 unverifiable-tail rule (§2.2). Tests (`WalV1TailTest`):
   - **SH-18 on v1:** `two-puts.wal`, byte 21 XOR `0x40`. Under both policies it throws, naming
     the offset (16), the claimed length (16,400) and the bytes present.
   - **The availability cost, asserted rather than hidden:** `two-puts.wal` cut short by one byte
     also throws "unverifiable". It recovered one record before, and v1 cannot prove it should.
   - **Every truncation offset of the three v1 fixtures** gives either exactly the complete
     records (the cut lies in a header) or the unverifiable error (the cut lies in a payload).
     Never fewer records without an error.
   - **Bit-flip at every byte of the three v1 fixtures:** every case throws
     `CorruptionException`, or is listed in the test as provably harmless, with the reason.
2. `fix(recovery)`: `WalWriter.close()` syncs, and only the newest segment may be torn (§2.3).
   Tests (`ShaleRecoveryBoundaryTest`):
   - two v2 segments, the older truncated mid-payload: `open` throws, naming it;
   - the same truncation in the newer one recovers the prefix;
   - a 1–15-byte newest segment opens empty, and the same in an older one throws;
   - `wal.sync.count` rises by one at a switch.
3. `test(recovery)`: a rejected open changes nothing. `ShaleRecoveryBoundaryTest` hashes every
   file name and its bytes before and after a rejected `open`, for each rejecting fixture:
   - SH-08 on v1;
   - SH-18 on v1;
   - SH-18 on v2;
   - a corrupt older segment.

   Each case asserts identical hashes apart from `*.tmp`, and that the review's engine-level
   history reports failure: `SYNC` A and B, close, mutate, `open` throws, and the WAL still
   exists.
4. `test(recovery)`: following the operator procedure from the exception message on the v1 SH-18
   fixture (truncate to the named offset, reopen) opens an empty database, and records the
   reopen as accepted loss in the test name.

**Done when:** all pass, and the review's two engine-level reproductions now fail loudly with
their inputs intact.

### Step 6 — SSTable structural validation (`m04-5/sstable-structure`), ~3 days
One `fix(sstable)` commit per row, each with its tiny fixture built in `SSTables` test helpers
(the review's round-3 parameters).

| Closes | Failing test first | Controls in the same commit |
|---|---|---|
| SH-14 | `BlockTest`: a 24-byte block whose restart count is `0x40000001` throws before any entry is read | negative count; offset outside the entry region; reversed offsets; a restart entry with `shared ≠ 0`; the writer's empty block still parses |
| SH-15 | `SSTableTest`: a flushed table whose footer's index handle is replaced by the metaindex handle (both CRCs intact) fails at `open` | footer handles overlapping; the index not ending at the footer; a data handle pointing into the metaindex. A valid empty table and an ordinary table still open, with point and scan results agreeing |
| SH-16 | `SSTableTest`: a valid-magic footer with index size `−1` (as unsigned 2^64 − 1), and one with 2^32, throw `CorruptionException`, asserted by exact type | an offset past the end of the file; `offset + size + 5` overflowing a `long` |

Also `test(sstable)`: extend `flippingAnyByte_isDetectedOrHarmless` to flip each **bit**
(8 × file length). A case counts as harmless only if the decoded contents equal the original's.

**Done when:** the three findings' fixtures reject; `GoldenSSTableTest` and the model test are
green.

### Step 7 — the case study, documentation and the tag (`m04-5/docs`), ~2 days
- `architecture/m4-5-integrity-boundaries.md`, which is the case study:
  - the invariant: recovery classifies only verified bytes;
  - the two counterexamples, with their exact bytes;
  - why the ZERO fix alone, `STRICT` alone, and a length-inclusive payload CRC were each not
    enough;
  - the v2 header and the v1 conservative rule;
  - the §2.2 decision table as a validated Mermaid flowchart (replacing M1's flowchart as the
    current one);
  - the test map;
  - **the limits:**
    - physical power loss is not tested;
    - a non-zero garbage tail is reported as corruption;
    - v1 tails cost availability;
    - CRC is not authentication.
- `package-info` for `dev.shale.internal.log`, with N9 citations: LevelDB `log_format.h` and
  `log_reader.cc`, SQLite's WAL frame header, PostgreSQL `XLogRecord`.
- Glossary rows (`naming.md` §1): *unwritten tail*, *unverifiable tail*, *header checksum*,
  *log framing* (*torn tail* is already there).
- [Bug log](../bug-log.md): move SH-08, SH-11, SH-13, SH-14, SH-15, SH-16 and SH-18 from the
  open register to full entries. Each entry gives:
  - finding;
  - fixing commit;
  - regression command;
  - actual output;
  - remaining limitation.
- `guides/embedding-shale.md`:
  - §5: the failure-model table gains the v2 framing tests;
  - §7: the unverifiable-v1-tail procedure;
  - §10: drop the rows these findings closed.

  The FAQ's corruption answer follows.
- README status row; a `CHANGELOG.md` entry; the completion plan's status table.
- **Reconciliation pass for M5:** its plan names `LogWriter`/`LogReader` as they shipped.
- Tag `m4.5-integrity`.

## 5. Milestone acceptance gates (each is a test named above)

- **No silent loss on the two reproduced faults:** SH-08 and SH-18 on v1 and v2 each fail
  loudly, and leave the directory unchanged (`ShaleRecoveryBoundaryTest`, `WalTest`,
  `WalV1TailTest`, `LogV2HeaderMatrixTest`).
- **Genuine torn appends still recover without an operator** in v2, at every truncation offset
  (`LogV2TruncationTest`, `ShaleCrashTest`).
- **Every bit of every v2 fragment header is protected** (`LogV2BitFlipTest`).
- **Decoders reject what they cannot represent,** through `CorruptionException` and nothing else
  (`VarintsTest`, `WalRecordCodecTest`, `BlockTest`, `SSTableTest`).
- **v1 is still readable:** all four v1 goldens (three WAL, one SSTable) decode.
- Every existing test stays green.

## 6. Not in M4.5

These are deliberately out of this milestone, each with its owner:
- the `Env` seam, `FaultInjectionEnv` and directory syncs (SH-04) → M5;
- the failed state (SH-01) → M5;
- rollback of a partly built `open` or cursor (SH-02, SH-10) → M5. Until then a rejected `open`
  can leak the SSTable handles it opened before the WAL failed: retry it a few times, not in a
  loop;
- numeric file names (SH-09) → M5;
- range pruning (SH-17) → M6;
- a recovery memory budget (SH-12) → M8.

Never in scope:
- automatic salvage, or a recovery tool;
- an SSTable format change;
- a whole-file checksum;
- coverage-guided fuzzing (Jazzer would need an ADR for the test-dependency allowlist, and the
  deterministic fixtures above come first).

## References

ADR-0024 and its sources; LevelDB `db/log_format.h`, `db/log_reader.cc`, `table/block.cc`,
`table/format.cc`; SQLite file format §4 (WAL frames); PostgreSQL `xlogrecord.h`; Pillai et al.,
"All File Systems Are Not Created Equal" (OSDI 2014); `on-disk-formats.md` §2–§4.
