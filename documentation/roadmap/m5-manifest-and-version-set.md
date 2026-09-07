# M5 — Manifest + recovery hardening: implementation plan

**Goal:** the database stops being "whatever `.sst` files are in the directory" and becomes an
explicit, atomically-installed, crash-recoverable **version** (ADR-0012). That makes file
deletion safe, which is what M6's compaction needs.

**Depends on:** M4 (the iterator seam and the cursor's pin), M3 (`SSTableReader`, flush), M1
(the block log this format reuses, `RecoveryPolicy`), M0 (`InternalKey`, `Varints`).

**Design locked in:** ADR-0012 and
`shale-core/src/main/java/dev/shale/manifest/format.md`. New on-disk format ⇒ `Format-Change:`
trailer, golden file, round-trip and bit-flip tests all required (on-disk-formats.md §3).

## Scope

**In M5:**
- `dev.shale.manifest`: `VersionEdit` (tagged payload codec), `ManifestWriter` /
  `ManifestReader` over the WAL block-log framing with the `"ShaleMAN"` magic, `Current` (read,
  and rewrite via temp + fsync + `ATOMIC_MOVE`).
- `Version` — the immutable live file set, reference-counted; `VersionSet` — the current
  version plus the file-number and sequence counters, and the single `install(edit)` path.
- **Delete-on-zero:** a table's file is deleted when the last `Version` referencing it is
  dropped *and* no cursor still pins it.
- `Shale.get` retains the version it probes and releases it after — closing the gap where a
  point read touches tables it does not own (`Shale.java:288-305`).
- Recovery becomes: read `CURRENT` → replay the manifest → replay only WAL segments numbered
  at or above the edit's log number → **rewrite the manifest** from a full snapshot.
- Comparator name persisted in the first edit and verified on open; a mismatch throws with
  both names.
- **The recovery-flush is removed.** Replayed records stay in the active memtable.
- Metrics: `manifest.edit.count`, `manifest.rewrite.count`, `sstable.deleted.count`.

**Deferred:** rewrite-when-large mid-run (M6 — open-time rewrite bounds growth per run);
levels beyond 0 (M6 — the `level` field exists and is always 0); compaction and its file
picking (M6); the `FaultyFileSystem` fault-injection wrapper beyond what these tests need
(M5 introduces it, M6 extends it); snapshots (M7).

## Task order (TDD; each task one commit, gate green)

1. **Design docs** — ADR-0012, `manifest/format.md`, this plan, ADR index. *(done)*
2. **`VersionEdit` + codec** — the tagged payload of format.md §4. Tests: every field
   round-trips; repeated tags 5/6 accumulate; an absent field means unchanged; **an unknown tag
   throws `CorruptionException` with its offset** (N4); a truncated field throws. Plus a jqwik
   round-trip property over arbitrary valid edits.
3. **`ManifestWriter` / `ManifestReader`** — the block log with the manifest magic. Prefer
   extracting the framing shared with `dev.shale.wal` over copying it; if the seam is not clean,
   say so in the commit and copy deliberately rather than contorting the WAL.
   Tests: round-trip; wrong magic rejected; **a torn edit at the tail is discarded** (the
   install never completed) while a bad CRC mid-file is `CorruptionException`.
4. **Golden + bit-flip** — birth `golden/manifest/v1/first-edit.manifest` with its `.json`
   sibling, pin its bytes in format.md §6, decode it in a test, and flip a bit at every offset
   asserting each is detected or provably harmless. Never regenerated (§4).
5. **`Current`** — read, and rewrite via `CURRENT.tmp` + `force()` + `ATOMIC_MOVE`. Tests: the
   four states in format.md §5, including `CURRENT` naming a missing manifest ⇒
   `CorruptionException`, and a stray `CURRENT.tmp` ignored and removed.
6. **`Version` + `VersionSet`** — immutable file set, retain/release, `install(edit)` =
   append + fsync + publish. Tests: install is visible only after the append; a version holding
   a file keeps it alive; dropping the last reference deletes the file.
7. **Wire the flush** — `switchAndFlush` installs an edit (file added, log number advanced)
   instead of mutating a list, then deletes the covered WAL segment. Test: the crash between
   rename and WAL delete now recovers to exactly one table (see invariants).
8. **`get` pins its version** — retain, probe, release, mirroring `ReconcilingCursor`. Test: a
   `get` racing an install never reads a released channel.
9. **Recovery + open-time rewrite** — `CURRENT` → manifest replay → bounded WAL replay → new
   manifest from a snapshot. Tests: reopening 1,000 times creates **zero** new tables; a crash
   *during* the rewrite leaves the old `CURRENT` valid; a comparator mismatch is refused.
10. **Harness** — the model test restarts far more often, and asserts no `.sst` appears from a
    restart alone; extend the crash test to truncate the *manifest* at every offset.
11. **Docs** — `manifest/package-info.java`; glossary rows for *version*, *version edit*,
    *manifest*, *delete-on-zero*; the M5 as-built architecture doc; update the engine
    `package-info` where recovery is described; **revisit ADR-0011's tie claim** (below).
12. **Finish** — `./gradlew build` + `crashTest` green; README status; M5 release note; tag
    `m5-manifest`. The format work commits with `Format-Change: manifest v1` and
    `Reversible: no`.

## Invariants to hold (checked by tests, not prose)

- **Install is atomic.** A version is visible only after its edit is appended and fsynced. A
  crash before that leaves the previous version; a crash after it leaves the new one. Nothing
  in between is observable.
- **No duplicate `(user key, sequence)` across live tables.** The crash between `Files.move`
  and the WAL delete is the case that produces one today. This is the named test for
  ADR-0011's claim that exact internal-key ties are unreachable: either M5 makes it true, or
  ADR-0011's tie-break note is corrected in the same commit. Do not let it pass silently.
- **A reopen is not a mutation.** Opening a database creates no SSTable. (It *does* write a new
  manifest — the one deliberate exception, stated in `open`'s Javadoc.)
- **Delete-on-zero.** A file is deleted only when no version and no cursor references it; a file
  still pinned by an open cursor survives the version that superseded it.
- **Bounded replay.** Only WAL segments at or above the manifest's log number are replayed.
- **N4 everywhere.** Unknown tag, bad CRC mid-file, wrong magic, non-zero reserved bytes, and a
  `CURRENT` naming a missing file are all `CorruptionException` with offsets — never a skipped
  record, never an "empty database".
- **N6 becomes correctness.** A leaked reference is now a leaked file and an over-release is a
  use-after-free; the reference count returning to its starting value is asserted, not assumed.
