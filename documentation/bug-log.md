# Bug log

Defects found in this project, with how each was caught and what now stops it from coming back.
Storage engines are judged by the bugs they do not ship. This page is the record of the ones the
process caught, including the embarrassing ones, because how a bug was found says more about a
project than a list of features.

**Rules for an entry:**
- **When:** a defect reached `main`, or reached a commit on a milestone branch, before it was
  found.
- **What it says:** what was wrong, the consequence it would have had, how it was found, and the
  guard that now exists.
- **Where it links:** the fixing commit.

Newest first.

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
