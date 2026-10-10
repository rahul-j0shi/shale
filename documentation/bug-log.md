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

**Why the process missed them.**
- **SH-08 and SH-18:** `wal/format.md` claimed a bit-flip test at every offset for the WAL; none
  existed (`on-disk-formats.md` §3 step 7 was never met for the WAL).
- **SH-13 to SH-16:** the other decoders were tested with round trips and payload byte flips,
  which cannot reach structural branches.

| Id | Defect | Shown by | Fixed in |
|---|---|---|---|
| SH-18 | **Silent loss.** One bit in a WAL fragment's length (16 → 16,400) makes the reader call a damaged header a torn tail before any CRC can be checked. `open` succeeds without the two acknowledged `SYNC` writes and deletes the WAL. A real one-byte truncation of the same file keeps one record. | fixture: two `SYNC` puts, byte 21 XOR `0x40` | [M4.5](roadmap/m4-5-integrity-boundaries.md) Steps 4–5 (ADR-0024) |
| SH-08 | **Silent loss.** A `FULL` → `ZERO` type bit makes the reader skip the block as padding before any CRC check. `open` succeeds empty and deletes the WAL. | fixture: byte 22, bit 0 | M4.5 Steps 3–4 |
| SH-01 | After a failed flush the engine keeps accepting writes. The failed flush's memtable drops out of the read view, and after reopen `get` returns an old value while `scan` returns the new one. Shown only by ignoring the guide's "reopen after `StorageException`"; nothing enforces that advice. | a directory placed at the next table's path; a retried write | [M5](roadmap/m5-manifest-and-recovery.md) §2.6, Step 7 |
| SH-04 | `SYNC` was documented as power-loss durable, but no directory entry is ever synced: not a new segment's, not a flushed table's. Source finding; no power-loss reproduction. | `WalWriter.open`, `Shale.flushToSSTable` | M5 Steps 4 and 8 |
| SH-09 | Files are written as `%06d` but discovered as exactly six digits: past number 999,999, the second reopen ignores the newer files and loses data. | `000001.wal` renamed to `999999.wal`, reopened twice | M5 Step 4 |
| SH-02 | A failed `open` leaks the table handles it opened before failing: ten retries left ten handles. | one valid table, then a corrupt one | M5 Step 7 |
| SH-10 | A cursor whose construction throws keeps its table references: one handle survived ten failed scans and `close()`. | a data block damaged under a running engine | M5 Step 7 |
| SH-11 | A 10-byte varint whose last byte overflows 64 bits decodes as 0. | `[0x80 ×9, 0x02]` | M4.5 Step 3 |
| SH-13 | A WAL key length of 2^32 + 9 is narrowed to 9 and accepted. | a hand-built record | M4.5 Step 3 |
| SH-14 | A 24-byte block claims 1,073,741,825 restart points; the offset arithmetic overflows and the block parses. | restart count `0x40000001` | M4.5 Step 6 |
| SH-15 | A footer whose index handle points at the (valid, empty) metaindex is accepted; the reopened table exposes no data. A crafted multi-field rewrite, not a one-bit fault. | footer re-encoded with CRCs intact | M4.5 Step 6 |
| SH-16 | Malformed handle sizes escape as `ArrayIndexOutOfBoundsException` or `ArithmeticException` instead of `CorruptionException` (N4). | index size −1, and 2^32 | M4.5 Step 6 |

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
