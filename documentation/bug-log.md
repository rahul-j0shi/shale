# Bug log

Defects found in this project, with how each was caught and what now stops it from coming back.
Storage engines are judged by the bugs they do not ship. This page is the record of the ones the
process caught, including the embarrassing ones, because how a bug was found says more about a
project than a list of features.

**Rules for an entry:**
- **When:** a defect reached `main`, or reached a commit on a milestone branch, before it was
  found. Until it is fixed it is a row in [Open defects](#open-defects), so a known defect is
  never only in someone's notes.
- **What it says:** what was wrong, the consequence it would have had, how it was found, and the
  guard that now exists.
- **Where it links:** the fixing commit.

Newest first.

## Open defects

Defects known to be on `main` and not yet fixed. Each row says what the defect is, how it was
shown, and the plan step that fixes it. When the fix lands, its row is replaced by a full entry
below, giving: the finding, the fixing commit, the regression command and its output, and the
limitation that remains.

These came from an external engineering review of `main` at `c66adea` (five rounds, 2026-10-08/09).
Its findings are numbered SH-01 to SH-18. Measurements and presentation findings are not defects;
the [completion plan §11](roadmap/completion-plan.md#11-the-october-2026-engineering-review-where-each-finding-is-closed)
gives every finding's owner.

The review's other seven defects (SH-08, SH-11, SH-13 to SH-16, SH-18) were fixed by M4.5: see
B5 and B6 below. The five left are M5's.

| Id | Defect | Shown by | Fixed in |
|---|---|---|---|
| SH-01 | After a failed flush the engine keeps accepting writes. The failed flush's memtable drops out of the read view, and after reopen `get` returns an old value while `scan` returns the new one. Shown only by ignoring the guide's "reopen after `StorageException`"; nothing enforces that advice. | a directory placed at the next table's path; a retried write | [M5](roadmap/m5-manifest-and-recovery.md) §2.6, Step 7 |
| SH-04 | `SYNC` was documented as power-loss durable, but no directory entry is ever synced: not a new segment's, not a flushed table's. Source finding; no power-loss reproduction. | `WalWriter.open`, `Shale.flushToSSTable` | M5 Steps 4 and 8 |
| SH-09 | Files are written as `%06d` but discovered as exactly six digits: past number 999,999, the second reopen ignores the newer files and loses data. | `000001.wal` renamed to `999999.wal`, reopened twice | M5 Step 4 |
| SH-02 | A failed `open` leaks the table handles it opened before failing: ten retries left ten handles. | one valid table, then a corrupt one | M5 Step 7 |
| SH-10 | A cursor whose construction throws keeps its table references: one handle survived ten failed scans and `close()`. | a data block damaged under a running engine | M5 Step 7 |

## B6 — Decoders that trusted a length or a count (M0–M4)

**What was wrong.** Five decoders believed a field before checking it in context:

| Id | Decoder | The field | What it allowed |
|---|---|---|---|
| SH-11 | `Varints.get` | a 10-byte varint's last byte | `[0x80 ×9, 0x02]` decoded as 0 |
| SH-13 | `WalRecordCodec.decode` | a key length, narrowed with `(int)` | 2^32 + 9 became 9 and matched the 9 bytes present |
| SH-14 | `Block` | the restart count, multiplied in `int` | a 24-byte block claimed 1,073,741,825 restarts and parsed |
| SH-15 | `SSTableReader.open` | the footer's handles, checked for nothing but encoding | a footer naming the empty metaindex as the index opened an empty table |
| SH-16 | `SSTableReader.readBlock` | a handle size, through `Math.toIntExact` | `ArithmeticException` or an out-of-bounds read instead of `CorruptionException` |

**Consequence.** Malformed input decoded as plausible data (SH-11, SH-13, SH-14), a valid table
read as empty (SH-15), or a corruption surfaced as an unrelated exception that a caller cannot
recognise (SH-16).

**Found by** the external review: tiny hand-built fixtures, most with every checksum valid.

**Why the process missed them:** every decoder was tested with round trips, and the SSTable with
one byte flip per offset. A round trip only feeds a decoder what its encoder writes, and a flip
rarely produces a *plausible* wrong field. Neither reaches a structural branch.

**Guard:**
- ADR-0024 §6's decoder contract: decode wide, check against the bytes remaining by subtraction,
  narrow last, consume exactly, throw `CorruptionException` and nothing else. No decoder catches
  `RuntimeException`.
- `on-disk-formats.md` §3 step 7 now asks for malformed structures with valid checksums, as well
  as flips at every bit.

**Fix:** `84d3183`, `fd5341d` (SH-11), `a1f64e0` (SH-13), `a01c028` (SH-14), `1d79264` (SH-15,
SH-16), `df8c6cf` (every bit of a table).

**Regression:** `./gradlew :shale-core:test --tests '*VarintsTest' --tests '*WalRecordCodecTest'
--tests '*sstable.BlockTest' --tests '*sstable.SSTableTest'` → `BUILD SUCCESSFUL`: 10, 8, 12 and
10 tests, 0 failures. Each finding's own fixture fails without its fix.

**Remaining limitation:** SSTables still have no whole-file checksum, which is deliberate; the
structural checks describe the v1 writer's layout, and a future format must restate them.

## B5 — One flipped bit in a WAL header lost every acknowledged write (M1–M4)

**What was wrong.** The WAL reader decided "torn tail" from header fields no checksum covered:
- **SH-08:** a `FULL` → `ZERO` type bit made it skip the block as padding, before any CRC check;
- **SH-18:** a `length` bit pushing the payload past the end of the file made it call the
  fragment a torn append and stop.

**Consequence.** With two `SYNC`-acknowledged writes in the WAL, one flipped bit made `open`
succeed with both writes missing, and the recovery flush then deleted the WAL. The engine's
central claim, that no acknowledged write is lost, was false for a single-bit fault.

**Found by** the external review's engine-level fixture (October 2026). SH-18 was found only after
a fix for SH-08 had been sketched: one header fault was not the whole boundary.

**Why the process missed it:** `wal/format.md` claimed a bit-flip test at every offset for the
WAL, and none existed (`on-disk-formats.md` §3 step 7 was never met). The crash test truncated a
valid file and never damaged a byte, so it could not have found either fault.

**Guard:**
- **Log framing v2** (ADR-0024): a CRC32C over each fragment header, verified before `length` or
  `type` is used. v1 segments are still read, with `ZERO` accepted only as an all-zero tail, and a
  short v1 payload refused as *unverifiable* rather than dropped.
- Only the newest segment may be torn, segments are synced when closed, and a refused `open`
  changes no file.
- The tests the WAL never had: every bit of every header, every cut, the review's matrix, and
  both faults as named regressions on v1 and v2.

The full account, with why the obvious patches were not enough, is the
[M4.5 case study](architecture/m4-5-integrity-boundaries.md).

**Fix:** `000f67b` (SH-08 on v1), `004a9c7` (format), `9505c5d`, `7c92861` (v2), `83d2eae`
(matrices), `9b1217f` (SH-18 on v1), `7f6573d` (newest-segment rule, sync on close).

**Regression:** `./gradlew :shale-core:test --tests '*LogV2HeaderMatrixTest' --tests
'*WalV1TailTest' --tests '*ShaleRecoveryBoundaryTest' --tests 'dev.shale.wal.WalTest'` →
`BUILD SUCCESSFUL`: 41, 8, 11 and 12 tests, 0 failures. Removing the header CRC check fails 17
framing tests; removing the v1 rule fails 7 of 8 v1 tail tests.

**Remaining limitations:** a genuine crash mid-payload in a pre-M4.5 (v1) segment now needs an
explicit truncation to open; physical power loss is not tested; directory entries are not synced
until M5 (SH-04).

## B4 — A guide that promised more than the engine kept (M4)

**What was wrong.** User-facing docs claimed more than the tests established:
- **SH-03.** The embedding guide said a cursor sees the database "as it was when opened". A cursor
  pins its tables, but it reads the live memtable, and a key inserted after it opens can appear
  in it.
- **SH-04.** `SYNC` was called power-loss durable without qualification.
- **SH-05.** The README, the FAQ and `wal/format.md` said every corruption is detected; the WAL had
  no bit-flip test at all.
- **SH-06.** The README led with SQL and a PostgreSQL protocol that do not exist yet.

**Consequence.** A user relying on cursor snapshots, or on `SYNC` across power loss, would have
been wrong without any test failing.

**Found by** the external review above, which reproduced the cursor behaviour.

**Guard:** every guarantee now names its failure model (ADR-0024 §7), and each milestone's docs
step re-checks the guide, FAQ and README against the tests it names.

**Fix:** the documentation change that added this entry. The Javadoc follows in M4.5 Step 1, and
the behaviour itself in M7 (snapshots) and M5 (directory syncs).

---

## B3 — Golden files that were never in the repository (M3)

**What was wrong.** `.gitignore` excludes `*.wal` and `*.sst`, to keep engine output and test
scratch out of the repository. The same rules silently excluded the golden fixtures:
- `golden/wal/v1/single-put.wal` (M1);
- `golden/sstable/v1/two-puts.sst` (M3).

Only their `.json` descriptions were tracked. The tests passed locally, where the files existed.

**Consequence.** A golden file exists to catch an unintended format change on a clean clone
(`conventions/on-disk-formats.md` §4). A golden file that is not in the repository catches
nothing, and the WAL golden test would have failed on any fresh checkout.

**Found by** reviewing what a clean clone would contain, before CI existed.

**Guard:**
- a negation rule in `.gitignore` re-includes everything under the golden resources directory;
- CI (below) now builds from a clean checkout on every push, so a missing fixture fails the build.

**Fix:** `15701c4`.

## B2 — A rule enforced only by a lint (M0–M4)

**What was wrong.** N1, "`shale-core` has zero runtime dependencies", was documented as "checked in
CI", but:
- there was no CI;
- the only mechanical check was Checkstyle's import ban.

An import ban catches a banned `import` line. It does not catch a dependency added to a build
file, or one arriving transitively.

**Consequence.** The project's central claim — everything hand-written — was asserted, not
enforced.

**Found by** auditing each "checked in CI" sentence against what actually ran.

**Guard:**
- `verifyNoRuntimeDependencies` resolves `shale-core`'s runtime classpath and fails the build if
  anything is on it. It is verified by mutation: adding a library made the task fail, naming the
  library and its transitive dependency.
- `.github/workflows/build.yml` runs `build` and `crashTest` on every push, on a clean JDK 25.

**Fix:** `0ddafc9`.

## B1 — A crash test that could fail with the wrong message (M1)

**What was wrong.** The WAL truncation test compared the recovered records with a prefix of the
written ones: `subList(0, recovered.size())`.

If recovery ever returned **more** records than were written, the test would have thrown an
`IndexOutOfBoundsException` from the test's own code. It would not have failed with an assertion
that named the problem.

**Consequence.** No wrong result would have passed. But the one failure this test exists to
catch — recovery inventing data — would have looked like a bug in the test.

**Found by** review of the test.

**Guard:** an explicit size-bound assertion before the prefix comparison. Every failure mode now
reports what it is: wrong content, wrong order, or too many records.

**Fix:** `e54ba8d`.
