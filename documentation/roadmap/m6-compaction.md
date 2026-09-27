# M6 — Compaction: implementation plan

**Status:** planned 2026-09-26; starts after M5.5 is tagged. **Depends on:** M5 (Versions,
VersionEdits, reference-counted obsolete-file deletion), M5.5 (background executor, stalls),
M4 (`MergingIterator`, which merges without interpreting so compaction can reuse it).

**Goal:** close the LSM loop. Background compaction merges SSTables into levels so that file
count, space and point-lookup cost stay bounded under a continuous write load, and the engine
counts write, read and space amplification so the RUM tradeoff can be measured, not asserted.
This is the milestone after which the project may call itself an LSM engine.

## Design (decided — ADR-0015 records it with the alternatives)

1. **Strategy order.** The original charter suggested size-tiered first as simpler. **Leveled
   first**, because LevelDB's `version_set.cc` is the clearest reference, it is the
   configuration ShaleDB will run on, and it keeps point lookups at one file per level. Size-tiered
   (Cassandra STCS) follows as the second, selectable policy, so M8 and D7 can measure the
   difference through the same workloads. The ADR states
   this deviation from the charter and why.
2. **Level invariants.** L0 files may overlap and are ordered by their sequence ranges. Each of
   L1..Ln is one sorted run of non-overlapping files. Target sizes grow by a fanout (default 10);
   all sizes are options so tests run with kilobytes.
3. **Sequence-correct point lookup.** M4's newest-file-first probe relies on flush chronology,
   which compaction breaks. New rule: probe memtables, then L0 files newest-first by largest
   sequence, then one candidate file per deeper level by key range. It is correct only if newer
   data for a key never sits below older data. LevelDB guarantees that by pulling every
   overlapping older L0 file into an L0→L1 compaction. The ADR states the invariant, and a
   test pins it.
4. **Picking.** Score = L0 file count ÷ trigger (4), or level bytes ÷ target. Compact the
   highest score ≥ 1. Choose the input file by a rotating per-level compaction pointer, then
   expand it to every overlapping file in the next level. Cut output files at the target file size and when
   grandparent overlap exceeds a limit.
5. **What a compaction drops.** Keep the newest version of each user key; drop older versions.
   Write the rule against a `smallestSnapshot` parameter (today: the latest sequence) so M7 only
   supplies the value. Drop a tombstone only when no level below the output can hold the key
   (LevelDB `IsBaseLevelForKey`); for size-tiered, only when no unselected run can hold it.
6. **Trivial move.** A single input with no overlap in the next level moves by VersionEdit
   alone, with no rewrite.
7. **Stalls.** Extend M5.5's stall with L0 slowdown and stop triggers (LevelDB: 8 and 12).
8. **`ShaleOptions`.** `Shale.open(Path, ShaleOptions)` replaces the growing parameter list:
   write-buffer size, compaction style, level sizes, triggers. This is a public API change.
9. **Manifest.** No format change: M5's manifest already records each file's level and defines
   tag 5, the compaction pointer.

## Scope

**In M6:** `dev.shale.compaction` — `Compactor` with `LeveledCompactor` and `TieredCompactor`
(the second after the first is complete and gated), the picker, the compaction job, trivial move,
installing through VersionEdit, the new point-lookup order, L0 stalls, the amplification counters,
and the soak tier.

**Deferred:** snapshots and the snapshot-aware drop rule (M7). **Not planned:** range tombstones
(ShaleDB's `DROP TABLE` uses batched point deletes), subcompactions, lazy leveling, compaction rate
limiting.

## Task order (TDD; each task one commit, gate green)

1. ADR, this plan, ADR index; `ShaleOptions` (refactor of `open`, behaviour unchanged).
2. Level-aware Version and the new point-lookup order, tested with hand-built Versions.
3. Compaction job: merge inputs, apply drop rules, cut outputs, install; tests with fixed inputs.
4. Leveled picker and compaction pointers; score tests from constructed level sizes.
5. Background scheduling on the M5.5 executor, L0 stalls, trivial move.
6. Crash coverage: extend M5's `ShaleCrashMatrixTest` with a compaction workload. Crash at every
   `FaultInjectionEnv` operation of a compaction (output writes, rename, manifest append, input
   deletion), then power loss, then reopen. Assert a valid Version, no lost key, and no
   obsolete input left behind once the reopen's cleanup runs.
7. Model harness: seeded forced compactions between operations and across restarts.
8. Amplification counters: `bytes.user.written`, `bytes.flush.written`,
   `bytes.compaction.read/written`, tables probed per `get`, live versus logical bytes, and
   **compaction debt** — the estimated bytes compaction must still write, per level (RocksDB's
   pending-compaction-bytes estimate). D6 attributes a share of it to each statement.
9. Soak test (hours, tagged `soak`): mixed workload with restarts, checked against the model;
   asserts flat file-descriptor count, bounded L0 and bounded total files.
10. `TieredCompactor` behind `ShaleOptions`, with its own tombstone rule and tests.
11. Docs: `compaction/package-info`, `architecture/m6-compaction.md` (level diagram, a compaction
    end to end, the drop-rule table), glossary, README status, changelog entry with measured
    write amplification for both policies.

## Acceptance gates

- **Bounded:** continuous `fillrandom` keeps L0 below the stop trigger and total file count
  proportional to data size ÷ target file size. No permanent stall.
- **Preserving:** property test — compaction preserves the newest visible version of every key;
  `get` and `scan` agree with the model before and after any compaction.
- **No resurrection:** a value in L2, its tombstone in L0; compacting L0→L1 keeps the tombstone
  until nothing below can hold the key. The size-tiered variant has the same test.
- **Point-lookup invariant:** a key overwritten across L0, L1 and L2 always returns its newest
  version, including after an L0 compaction that leaves newer L0 files behind.
- **Crash-safe:** every compaction crash point recovers; obsolete inputs disappear only after
  the last reader releases them; no committed file is deleted.
- **Counted:** amplification counters match hand-computed values on a small scripted scenario.
- **Soak:** a 1-hour soak run is green before the tag; the changelog entry records the command.

## References

O'Neil et al., "The Log-Structured Merge-Tree" (1996); LevelDB `db/version_set.cc`
(`PickCompaction`, `IsBaseLevelForKey`) and `doc/impl.md`; RocksDB wiki "Leveled Compaction",
"Universal Compaction", "Write Stalls"; Cassandra STCS docs; Luo & Carey, "LSM-based Storage
Techniques: A Survey" (VLDB J. 2020); Petrov, *Database Internals* ch. 7; mini-lsm week 2.
