# M6 — Compaction: implementation plan

**Status:** planned; starts after M5.5 is tagged. **Depends on:**
- M5: `Version`, `VersionSet.logAndApply`, file deletion on the last release, the manifest's
  tag 5 and per-file sequence range, and `FaultInjectionEnv`;
- M5.5: the single background thread and task, the stall wait, `ManualExecutor`;
- M4: `MergingIterator`, which merges without interpreting, so compaction reuses it.

**ADR:** 0015. **Estimate:** 4–6 focused weeks, in 8 steps.

**Goal.** Close the LSM loop. A background compaction merges SSTables so that file count, disk
space and point-lookup cost stay bounded under a continuous write load. Two selectable policies —
leveled and size-tiered — make the read/write/space tradeoff measurable. The engine counts write,
read and space amplification and **compaction debt**, the deferred cost at the heart of the
project's purpose. After M6 the project is an LSM engine.

---

## 1. Where the code will be at the start (after M5.5)

| Fact | Where |
|---|---|
| Every table is in level 0; nothing moves tables between levels; nothing deletes a live table | `Version`, flush |
| `get` probes memtables, then every table newest-first, stopping at the first that holds the key | `Shale.get` |
| One background thread runs one task at a time; the task flushes the oldest immutable memtable if there is one | M5.5 §2.3 |
| The writer thread stalls on `roomAvailable` when immutables are at their bound | M5.5 §2.4 |
| Each file's metadata carries its key range and sequence range (manifest tag 7) | M5 §2.2 |

## 2. The design (decided — ADR-0015 records it with the alternatives)

### 2.1 Policies

- **Leveled (the default, LevelDB's).**
  - Level 0 holds flush outputs, which may overlap. Levels 1–6 are each one sorted run of files
    with non-overlapping key ranges.
  - Level *n*'s target size is `maxBytesForLevelBase` (L1, default 10 MiB) × `levelSizeMultiplier`
    (default 10)^(n−1).
  - Output files are cut at `targetFileSizeBytes` (default 2 MiB).
- **Size-tiered (RocksDB's "universal" style, *not* Cassandra's STCS).**
  - Every file is one sorted run in level 0, and runs are kept **in age order**.
  - A compaction only ever merges **contiguous** runs, so age order survives every merge.
  - Cassandra-style STCS is rejected because it can merge an old and a new run while skipping a
    middle one. The output would then hold versions both older and newer than the skipped run,
    and "newest source first" point lookups would return stale data.
- **The choice** is `ShaleOptions.compactionStyle`, `LEVELED` or `TIERED`. It is not persisted;
  the layout on disk says enough.
  - **Leveled** opens a tiered layout as an overloaded level 0 and compacts it down.
  - **Tiered** refuses to open a database with files above level 0 (`IllegalArgumentException`),
    because it has no way to fit them into its age order.

### 2.2 Ordering, and the point-lookup rule

- **Level 0 (and every tiered run)** is ordered by `largestSequence`, descending. This is not file
  number: a compaction output gets a new number but holds old data.
- **`get`:**
  1. memtables, newest first;
  2. every level-0 file whose key range contains the key, in that order;
  3. then for each level 1..6, the single file whose range contains the key (binary search on
     largest key).

  It stops at the first source holding any version of the key.
- **Why that is correct — the invariant.** For any user key, a newer version never sits in a
  source that is probed later than an older one. Two rules maintain it:
  - **Leveled:** a level-0 compaction takes the chosen file *and every level-0 file overlapping the
    growing key range, transitively* (LevelDB's `GetOverlappingInputs` on level 0). So no newer
    level-0 file is ever left above data it overlaps that moved down. Every compaction from level
    *n* takes all overlapping files of level *n+1*.
  - **Tiered:** contiguity (§2.1).
- **`scan`** is unaffected: it merges every source and reconciles by sequence.

### 2.3 Leveled picking (LevelDB's `PickCompaction`)

1. **Scores.**
   - Level 0: file count ÷ `l0CompactionTrigger` (default 4).
   - Levels 1–5: level bytes ÷ target.
   - Level 6: never picked.
2. **What to compact.** If the highest score is ≥ 1, compact that level; otherwise do nothing.
3. **Input from level *n*** (*n* ≥ 1): the first file whose largest key is after the level's
   compaction pointer, wrapping to the first file. Level 0: the oldest file, then expand
   transitively over overlapping level-0 files.
4. **Inputs from level *n+1*:** every file overlapping the inputs' key range.
5. **Grandparents** (level *n+2* files overlapping the range) are recorded, for output cutting.
6. **Compaction pointer:** level *n* ← the largest key of the level-*n* inputs. It is written to
   the edit (tag 5).
7. **Trivial move.** A single level-*n* input, with no level-*n+1* overlap, and grandparent overlap
   ≤ 10 × `targetFileSizeBytes`, becomes an edit (delete from *n*, add to *n+1*) with no rewrite.

### 2.4 Tiered picking (RocksDB universal, simplified)

- **Trigger:** runs ≥ `tieredMinRuns` (default 4).
- **Candidate:**
  1. start at the *newest* run;
  2. extend to the next older run while that run's size ≤ (sum of the sizes taken so far) ×
     (1 + `tieredSizeRatioPercent`/100), default 100%;
  3. a candidate needs ≥ 2 runs.

  If there is no candidate, force-merge the newest `tieredMinRuns` runs.
- **Output:** one file. Its sequence range spans the inputs', so it lands in the same age slot.

### 2.5 Executing a compaction (`CompactionJob`)

**The merge.** Acquire the Version the compaction was picked from, and hold it for the job. Open
one `InternalIterator` per input (levels ≥ 1: a concatenating iterator over that level's files),
and merge them with `MergingIterator`. For each entry, in merged order, decide drop or keep with
LevelDB's rule:

```text
currentUserKey = none; lastSequenceForKey = MAX
for each (userKey, sequence, type):
  if userKey != currentUserKey: currentUserKey = userKey; lastSequenceForKey = MAX
  drop = false
  if lastSequenceForKey <= smallestSnapshot:          // a newer entry, visible to every snapshot, hides this
    drop = true
  else if type == DELETE && sequence <= smallestSnapshot && isBaseLevelForKey(userKey):
    drop = true                                         // nothing older can exist below the output
  lastSequenceForKey = sequence
  if not drop: write to the current output
```

- **`smallestSnapshot`** is `visibleSequence` in M6 (no snapshots yet); M7 passes the oldest live
  snapshot.
- **`isBaseLevelForKey`:**
  - leveled: no file in any level below the output level has a range containing the key;
  - tiered: the compaction includes the oldest run.
- **Cutting outputs (leveled):** start a new output file when the current one reaches
  `targetFileSizeBytes`, or when the grandparent bytes it overlaps exceed 10 ×
  `targetFileSizeBytes`. Never cut between two versions of the same user key.
- **Installing:** each output is written like a flush table (`.tmp`, sync, rename, sync the
  directory). Then one `logAndApply` with: every input deleted, every output added, the
  compaction pointer. Inputs become obsolete and are deleted when the last reader releases them
  (M5 §2.5).
- **No conflicts are possible.** Compaction runs on the one background thread, and the same thread
  performs flushes. So two edits are never computed from the same Version at once.

### 2.6 Scheduling and stalls

- **Priority:** the background task (M5.5 §2.3) does: flush the oldest immutable if one exists;
  else run one compaction if the picker returns one; else nothing.
- **Re-scheduling:** after each flush or compaction, the task re-submits itself if more work is
  pending.
- **Stall:** at group step 2 (M5.5 §2.1), the writer thread also waits while level-0 has ≥
  `l0StopWritesTrigger` files (default 12). The same `roomAvailable` condition is signalled after
  each compaction. Metric: `write.stall.count` with trigger tag `l0`.
- **Not planned:** a slowdown before the stop.

### 2.7 Manual flush and compaction (public API)

`Shale.flush()` switches and flushes the active memtable and waits for it. `Shale.compactRange(from,
to)` (LevelDB's `CompactRange`; `null` bounds mean everything) compacts every file overlapping the
range down to the last non-empty level (tiered: one run), and waits. Both run their work on the
background thread and block the caller until it completes. Tests and benchmarks need a way to
reach a known shape; D7 exposes them as SQL `CHECKPOINT`.

### 2.8 Amplification counters and compaction debt

| Counter | Meaning |
|---|---|
| `bytes.user.written` | key + value bytes accepted by `put`/`delete` |
| `bytes.wal.written`, `bytes.flush.written`, `bytes.compaction.written`, `bytes.compaction.read` | the I/O they name |
| `get.sources.probed` (histogram) | memtables + tables probed per `get` — read amplification |
| `compaction.count`, `compaction.trivial_move.count`, `compaction.duration` | work done |
| `compaction.debt.bytes` (gauge, per level and total) | the estimate below |
| `level.<n>.files`, `level.<n>.bytes` (gauges, updated at every install) | the shape of the tree; D6's `shale_levels` system table reads them |
| `sstable.open.count` (gauge) | open table readers; the soak test's resource check |

- **Write amplification** = (`bytes.flush.written` + `bytes.compaction.written`) ÷
  `bytes.user.written`. A second figure adds `bytes.wal.written` to the numerator.
- **Space amplification** = live table bytes ÷ logical bytes. Logical bytes are the latest
  version of every key; they are computed on demand by a full scan, in tests and benchmarks only.
- **Compaction debt (leveled):** Σ over levels *n* ≥ 1 of max(0, bytes(*n*) − target(*n*)) ×
  (`levelSizeMultiplier` + 1). Plus, if level 0 has ≥ `l0CompactionTrigger` files: bytes(L0) × 2.
  **Tiered:** the total size of the runs the picker would merge now. It is an estimate of bytes
  compaction must still write, and the Javadoc says so and cites RocksDB's
  `EstimatedPendingCompactionBytes`, whose shape it follows.

## 3. New and changed types

| Type | Package | Step |
|---|---|---|
| `ShaleOptions` | `dev.shale` — adds `compactionStyle`, `l0CompactionTrigger`, `l0StopWritesTrigger`, `maxBytesForLevelBase`, `levelSizeMultiplier`, `targetFileSizeBytes`, `tieredMinRuns`, `tieredSizeRatioPercent` | 2 |
| `CompactionStyle` (enum `LEVELED`, `TIERED`) | `dev.shale` | 2 |
| `Version` | `dev.shale.manifest` — files per level in the §2.2 order; `overlapping(level, smallest, largest)`; `isBaseLevelForKey`; `levelBytes(level)`; `lookupOrder(userKey)` | 2 |
| `Compaction` (record: level, inputs, next-level inputs, grandparents, output level, trivial move) | `dev.shale.compaction` | 3 |
| `CompactionJob` | `dev.shale.compaction` | 3 |
| `ConcatenatingIterator` (one level's files as one `InternalIterator`) | `dev.shale.iterator` | 3 |
| `CompactionPicker` (interface), `LeveledPicker`, `TieredPicker` | `dev.shale.compaction` | 4, 6 |
| `CompactionDebt` (the estimate) | `dev.shale.compaction` | 5 |
| `Shale.flush()`, `Shale.compactRange(from, to)` | `dev.shale` | 4 |

## 4. Steps

### Step 1 — ADR-0015 (`adr/0015-compaction`), ~1 day
`docs(compaction)`: ADR-0015 records §2. Alternatives:
- size-tiered first (rejected: less clear reference, and not what ShaleDB runs on);
- Cassandra STCS (rejected: §2.1);
- a thread per compaction (rejected: one background thread removes edit conflicts entirely);
- slowdown triggers (rejected for now: a stop trigger is enough to bound level 0).

Update the index. **Done when:** merged.

### Step 2 — levels and the point-lookup order (`m06/levels`), ~4 days
1. `feat(api)`: the `ShaleOptions` fields and `CompactionStyle`.
2. `feat(manifest)`: `Version` per-level structure and queries.
3. `feat(api)`: `Shale.get` follows `Version.lookupOrder`.
4. **Tests** (`VersionLevelsTest`, `ShaleLookupOrderTest`), with Versions built through
   `logAndApply` of hand-written edits that place files in levels:
   - level-0 order by largest sequence, including a file whose *number* is larger but whose data
     is older;
   - a key with versions in L0, L1 and L2 returns the newest;
   - a key only in L3 is found;
   - `get.sources.probed` is right in each case.

**Done when:** those tests and every existing test pass. No compaction runs yet.

### Step 3 — the compaction job (`m06/compaction-job`), ~5 days
1. `feat(iterator)`: `ConcatenatingIterator`, with tests: seek across file boundaries, empty
   level.
2. `feat(compaction)`: `Compaction` and `CompactionJob` (§2.5), independent of any picker.
3. **Tests** (`CompactionJobTest`), fixed inputs written with `SSTableWriter`:
   - the newest version of each key survives, older ones are dropped;
   - a tombstone is dropped at the base level and kept when a lower level holds the key (the
     resurrection test);
   - outputs are cut at the target size and at the grandparent limit, never between two versions
     of one key;
   - the edit deletes every input and adds every output;
   - property: for random inputs, `scan` of the result equals `scan` of the inputs.

**Done when:** `CompactionJobTest` is green.

### Step 4 — leveled compaction in the engine (`m06/leveled`), ~5 days
1. `feat(compaction)`: `LeveledPicker` (§2.3) with trivial move.
2. `feat(api)`: the background task runs compactions (§2.6); the level-0 stop trigger;
   `flush()` and `compactRange` (§2.7).
3. **Tests:**
   - `LeveledPickerTest`: scores from built Versions; pointer rotation; transitive level-0
     expansion; trivial-move eligibility.
   - `ShaleCompactionTest`, with `ManualExecutor`:
     - writes that create 5 level-0 files, then `runAll()`: level 0 has fewer than 4 files and
       every key reads back;
     - obsolete inputs are deleted after the last cursor over them closes, not before;
     - the stall releases after a compaction;
     - `compactRange(null, null)` leaves one sorted run with no tombstones and every live key.
   - **Bounded** (`ShaleBoundedFilesTest`): 200,000 random `put`s with tiny sizes and `runAll()`
     after every flush. Level 0 stays below the stop trigger; total files ≤ 2 × (live bytes ÷
     target file size) + 10.

**Done when:** these tests and every existing test pass.

### Step 5 — counters and compaction debt (`m06/amplification`), ~2 days
1. `feat(compaction)`: every §2.8 counter, and `CompactionDebt`.
2. **Test** (`AmplificationCountersTest`): a scripted scenario with tiny fixed sizes, where every
   counter's expected value is computed by hand in the test's comments. That includes the debt
   before and after one compaction.

**Done when:** exact matches.

### Step 6 — size-tiered (`m06/tiered`), ~4 days
1. `feat(compaction)`: `TieredPicker` (§2.4); `isBaseLevelForKey` for tiered; the open-time check
   of §2.1.
2. **Tests:**
   - `TieredPickerTest`: candidate selection by size ratio; forced merge.
   - `ShaleTieredTest`: the same "bounded", "resurrection" and "lookup order" scenarios under
     `TIERED`; a merge of the two oldest runs keeps age order; opening a leveled database as tiered
     fails; the reverse works.

**Done when:** these tests are green.

### Step 7 — the harnesses (`m06/harness`), ~4 days
1. `test(memtable)`: `EngineModelTest` runs both styles, with background tasks at seeded points,
   restarts included.
2. `test(recovery)`: `ShaleCrashMatrixTest` gains a compaction workload: crash at every operation
   of one compaction (output writes, renames, manifest append, input deletions), power loss,
   reopen. Assert:
   - every key is present;
   - the Version is valid (the §2.2 invariant holds);
   - no obsolete file remains after the reopen's cleanup.
3. `test(api)`: `ShaleSoakTest` (tag `soak`), the first real soak.
   - **Run:** a seeded mixed workload with restarts, checked against the model every 10,000
     operations, for `-Dshale.soak.minutes` (default 60).
   - **Assert flat resources:** the engine's `sstable.open.count` gauge equals the live file count
     at every check. That is the portable replacement for counting file descriptors, which would
     need a banned `com.sun` API.
   - **Assert bounded levels:** level 0 stays below the stop trigger.

**Done when:** model and crash matrix green in CI; one 60-minute soak green locally, its command
recorded in the changelog.

### Step 8 — documentation and the tag (`m06/docs`), ~2 days
- `compaction/package-info`, and N9 citations (LevelDB `version_set.cc`, `db_impl.cc`
  `DoCompactionWork`; RocksDB universal compaction; O'Neil et al. 1996).
- `architecture/m6-compaction.md`: a level diagram, one compaction traced end to end, the drop-rule
  table, the invariant of §2.2 with its proof sketch.
- Glossary rows: sorted run, compaction pointer, trivial move, grandparent, compaction debt, base
  level.
- A changelog entry with the measured write amplification of both styles on the bounded workload;
  README status; the completion plan's status table.
- **Reconciliation pass for M7.**
- Tag `m6-compaction`.

## 5. Milestone acceptance gates

- **Bounded:** file count and level 0 stay bounded under continuous writes, in both styles
  (`ShaleBoundedFilesTest`, `ShaleTieredTest`).
- **Correct:** compaction preserves the newest version of every key (the `CompactionJobTest`
  property, the model test); no resurrection; the lookup-order invariant (`ShaleLookupOrderTest`).
- **Crash-safe:** every compaction crash point recovers, and inputs are deleted only when
  obsolete and unreferenced.
- **Counted:** amplification and debt counters match hand computation.
- **Soak:** 60 minutes green, with flat open-table count.

## 6. Not in M6

Snapshots and the snapshot-aware `smallestSnapshot` (M7); range tombstones; subcompactions;
parallel compactions; lazy leveling; rate limiting; slowdown triggers.

## References

LevelDB `db/version_set.cc` (`PickCompaction`, `SetupOtherInputs`, `IsBaseLevelForKey`,
`Finalize`), `db/db_impl.cc` (`DoCompactionWork`, `BackgroundCompaction`), `doc/impl.md`; RocksDB
wiki "Leveled Compaction", "Universal Compaction", "Write Stalls", and
`EstimatedPendingCompactionBytes`; O'Neil et al., "The Log-Structured Merge-Tree" (1996); Luo &
Carey (VLDB J. 2020); Petrov, *Database Internals* ch. 7.
