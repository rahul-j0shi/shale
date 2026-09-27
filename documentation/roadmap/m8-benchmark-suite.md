# M8 — Engine benchmarks: Shale 1.0, measured: implementation plan

**Status:** planned; starts after M7 is tagged. **Ends the engine phase** with the tag `shale-1.0`.
**Depends on:**
- M6: the amplification and compaction-debt counters, both compaction styles;
- M7: bloom filters and `OperationStats`;
- M5.5: group commit.

**ADR:** 0023 (online checkpoint; public API, `Reversible: no`). ADR-0013 already allows RocksDB
as a benchmark-only baseline. **Estimate:** 3–4 focused weeks, in 8 steps.

**Goal.** Two things make the engine a 1.0 someone else could adopt.
- **Every performance claim becomes a measurement with context.** Each experiment moves one
  storage knob and shows what it does to read, write and space cost — the RUM tradeoff made
  concrete. RocksDB runs the same workloads, so a reader can tell "slow" from "fine for a
  hand-written engine". D7 later repeats the key experiments through SQL.
- **The engine can be operated, not only called.**
  - There is an online, consistent backup (`checkpoint`).
  - An operating guide says how to size, tune, monitor, back up and recover it.
  - A stated compatibility policy says what 1.0 promises.
  - The jar is attached to a release.

  These answer the first questions a prospective user asks after "does it work?"

---

## 1. Where the code will be at the start (after M7)

| Fact | Where |
|---|---|
| `shale-bench` has the JMH plugin and M5.5's `WritePathBenchmark` / `ReadBenchmark`, plus M7's `GetOverheadBenchmark` | `shale-bench/src/jmh/java` |
| `-PjmhIncludes` / `-PjmhThreads` select one JMH run | `shale-bench/build.gradle.kts` (M5.5) |
| Every knob to sweep is a `ShaleOptions` field | M5–M7 |
| Checkstyle bans `org.rocksdb` imports in every module | `config/checkstyle/checkstyle.xml` |

## 2. The design (decided)

### 2.1 Two harnesses, two jobs

- **JMH** (`src/jmh/java/dev/shale/bench/micro`) measures code paths in isolation: coding,
  block seek, skiplist, merge step, bloom probe, CRC32C, stats overhead. It exists to support or
  refute `perf` commits.
- **The workload runner** (`src/main/java/dev/shale/bench/workload`) measures the engine as a
  system, over minutes, db_bench-style.
  - It is a plain `main`, run by a Gradle `JavaExec` task `:shale-bench:workload`. JMH is the
    wrong tool for multi-minute runs with background compaction.
  - It drives either engine through one interface:

    ```java
    interface KvStore extends AutoCloseable {
      void put(byte[] key, byte[] value, boolean sync);
      byte[] get(byte[] key);
      int scan(byte[] from, int limit);                 // returns entries seen
      Map<String, Long> counters();                      // engine counters, for amplification
    }
    // ShaleKvStore (ShaleOptions from the command line), RocksDbKvStore (matched options)
    ```

### 2.2 Workloads

| Name | What it does |
|---|---|
| `fillseq` | N puts, keys in order |
| `fillrandom` | N puts, keys in random order |
| `overwrite` | N puts over the keys of an existing database |
| `readrandom` | N gets of existing keys |
| `readmissing` | N gets of absent keys |
| `seekrandom` | N scans of 10 entries from random existing keys |
| `readwhilewriting` | one writer at a fixed rate, R readers doing `readrandom`, for a duration |

- **Sizes:** keys are 16 bytes, values 100 bytes, all generated from a seed.
- **Presets:**
  - `quick`: N = 50,000 — for CI, where it only proves the runner works;
  - `full`: N = 1,000,000, about 116 MB logical — for the report.

### 2.3 What each run records

- **Output:** one CSV row per run, in `shale-bench/build/results/`:
  - the environment: commit SHA, JDK, JVM flags, CPU model, core count, RAM, disk, filesystem;
  - every option;
  - the results: ops/s; p50, p99, p99.9 latency; write amplification; space amplification;
    `get.sources.probed` mean; the peak compaction debt.
- **Latency** comes from a hand-written log-bucketed histogram (HdrHistogram's design: 2
  significant digits, `long` counts).
- **Repetition:** every point runs 3 times; the report shows the median and the min–max spread.
  Raw results are never committed (`commits.md` §3).

### 2.4 The RocksDB baseline

- **The dependency:** `org.rocksdb:rocksdbjni`, a pinned version in `libs.versions.toml`, on
  `shale-bench`'s `main` configuration only.
- **Checkstyle:** the dependency-ban `IllegalImport` module gets `id="bannedDependencies"`. A
  Checker-level `SuppressionSingleFilter` suppresses that id for files under
  `shale-bench/src/main/java/dev/shale/bench/baseline/` only.
- **Matched options:** the same memtable size, the same bloom bits, compression off, the WAL on,
  the same sync mode. `RocksDbKvStore` lists each setting with a comment naming the Shale option
  it matches.
- **The module-graph check.** A root task, `verifyModuleGraph`, runs in `check` and fails if any
  project's dependency on another project is not in this allowed list:
  - `shale-bench → shale-core`, and later `shale-db`;
  - D1+ adds `shale-db → shale-core`, `shale-server → shale-db`, `shale-demo → none` (it speaks the
    wire protocol).

  It encodes CLAUDE.md §2 mechanically, and guarantees no production module depends on
  `shale-bench`.

### 2.5 Experiments (each one chart in the report)

| # | Sweep, everything else at defaults | Shows |
|---|---|---|
| E1 | `compactionStyle` leveled vs tiered, on `fillrandom` then `readrandom` | write amplification vs read and space amplification |
| E2 | `bloomBitsPerKey` 0 / 5 / 10 / 15, on `readmissing` | filter memory vs lookup latency and `get.sources.probed` |
| E3 | `SYNC` writers 1 / 4 / 16 / 64, on `fillrandom` | group commit: throughput and mean group size |
| E4 | `writeBufferSizeBytes` 1 / 4 / 16 MiB, on `fillrandom` | write amplification and flush count |
| E5 | `readwhilewriting`, 5 minutes | p99 / p99.9 read latency over time, with compaction debt on the same time axis |
| E6 | Shale vs RocksDB, on `fillrandom`, `readrandom`, `readmissing` | throughput and amplification side by side, and the mechanisms behind the gap |
| E7 | E5 under G1 (the default) and generational ZGC (`-XX:+UseZGC`), with `-Xlog:gc` | how much of the tail latency is the garbage collector: p99 and p99.9 beside the total and longest GC pauses |

**Why E7.** "It's Java — what about GC pauses?" is the first question anyone asks of a JVM storage
engine. E7 answers it with a number, not an opinion. It costs one extra run of an existing
workload.

### 2.6 Online checkpoint (ADR-0023, after RocksDB's `Checkpoint`)

`Shale.checkpoint(Path target)` writes a consistent copy of the database into a new directory
while the engine keeps serving reads and writes. The copy is itself a database: `Shale.open(target)`
opens it, and restoring means opening the copy, or copying it into place.

```java
/**
 * Writes a consistent copy of this database to {@code target}, which must not exist. The copy
 * holds every write acknowledged before this call, and possibly some writes made during it,
 * always as a prefix in sequence order.
 */
public void checkpoint(Path target);
```

**The algorithm:**
1. **`flush()`** (M6), so every write acknowledged before the call is in an SSTable. The copy
   then needs no WAL.
2. **Pin.** Under the version lock, take the current `Version` and `retain()` it (M5). Its tables
   cannot be deleted by compaction until released.
3. **Build the copy in a sibling directory** `target` + `.checkpoint-tmp`, created fresh (an
   existing one is deleted first: it can only be a crashed checkpoint's).
   - Hard-link every table of the pinned `Version` into it: `Env.link`, which is new. SSTables
     are immutable, so a link is a copy that costs no space.
   - A target on another filesystem cannot be linked. `Env.link` then throws, and the table is
     copied and synced instead.
4. **Its metadata:**
   - write `MANIFEST-000001` with one full-state edit of the pinned `Version` (M5 open step 8's
     encoding), and sync it;
   - write `CURRENT`, and sync it;
   - sync the directory.
5. **Commit point.** Rename the temporary directory to `target`, and sync `target`'s parent.
   `// DURABILITY:` — after this, the checkpoint is complete, and before it `target` does not
   exist.
6. **Release** the `Version`.

**Why the rename.** Without it, a crash midway leaves a directory that `Shale.open` would treat as
a new, empty database: a backup that silently lost everything. With it, `target` either does not
exist or is complete.

**Also:**
- **Errors:** as any other operation (`StorageException`, the failed state).
- **Concurrency:** a checkpoint runs on the caller's thread, one at a time (a lock).
- **Cost:** it blocks nothing but other checkpoints; compaction continues around the pin.
- **The `Env` change:** `Env.link(Path existing, Path newLink)` is added to the public SPI.
  - `PosixEnv` uses `Files.createLink`.
  - `FaultInjectionEnv` treats a link like a new file: lost unless its directory is synced.

## 3. New and changed types and files

| Item | Where | Step |
|---|---|---|
| `KvStore`, `ShaleKvStore`, `WorkloadRunner` (main), `Workload` (enum of §2.2), `LatencyHistogram`, `KeyGenerator`, `ResultWriter` (CSV), `Environment` (capture) | `shale-bench/src/main/java/dev/shale/bench/workload` | 2 |
| `:shale-bench:workload` task; `scripts/bench.sh` (`quick` / `full`, a style, a store) | `shale-bench/build.gradle.kts`, `scripts/` | 2 |
| JMH micro benchmarks | `shale-bench/src/jmh/java/dev/shale/bench/micro` | 3 |
| `RocksDbKvStore` | `shale-bench/src/main/java/dev/shale/bench/baseline` | 4 |
| `bannedDependencies` id + `SuppressionSingleFilter` | `config/checkstyle/checkstyle.xml` | 4 |
| `verifyModuleGraph` | root `build.gradle.kts` | 4 |
| `documentation/benchmarks/engine.md` | `documentation/benchmarks/` | 5 |
| ADR-0023 | `documentation/adr/0023-online-checkpoint.md` | 6 |
| `Shale.checkpoint(Path)`; `Env.link`; `metrics`: `checkpoint.count`, `checkpoint.duration` | `dev.shale`, `dev.shale.env` | 6 |
| `CheckpointTest`, `ShaleCrashMatrixTest` checkpoint workload | `shale-core/src/test`, `src/crashTest` | 6 |
| `documentation/guides/operating-shale.md`; updates to `embedding-shale.md` and `faq.md` | `documentation/guides/`, `documentation/` | 7 |
| The release: version `1.0.0`, jars attached | `build.gradle.kts`, GitHub release `shale-1.0` | 8 |

## 4. Steps

### Step 1 — the plan's review (`docs/m08-benchmark-plan`), ~½ day
`docs(bench)`: confirm §2's presets fit the machine the report will be run on (RAM ≥ 4 × `full`
logical size, a local SSD). Record the machine in `documentation/benchmarks/engine.md`'s method
section, stub only. **Done when:** merged.

### Step 2 — the workload runner (`m08/workload-runner`), ~4 days
1. `feat(bench)`: every type in the first row of §3, and `ShaleKvStore`.
2. `build(bench)`: the `workload` task; `scripts/bench.sh`.
3. **Tests** (`shale-bench/src/test`):
   - `LatencyHistogramTest`: percentiles against a sorted-array oracle, within the bucket error;
   - `KeyGeneratorTest`: the same seed gives the same sequence; `fillseq` is sorted;
   - `WorkloadRunnerTest`: `quick` `fillrandom` + `readrandom` on Shale writes one well-formed CSV
     row.

**Done when:** `scripts/bench.sh quick` runs every workload on Shale.

### Step 3 — the micro benchmarks (`m08/micro`), ~2 days
`test(bench)`: `CodingBenchmark` (varint, fixed64), `BlockSeekBenchmark`, `SkiplistBenchmark`
(insert, seek), `MergeStepBenchmark`, `BloomProbeBenchmark`, `Crc32cBenchmark`; `@Fork(2)
@Warmup(3) @Measurement(5)`, results consumed through `Blackhole`. **Done when:** each runs through
`-PjmhIncludes`.

### Step 4 — the RocksDB baseline and the module-graph check (`m08/baseline`), ~2 days
1. `build(bench)`: the pinned `rocksdbjni` dependency; the Checkstyle id and scoped suppression.
2. `feat(bench)`: `RocksDbKvStore` with the matched options.
3. `build(build)`: `verifyModuleGraph` in `check`.
4. **Tests:**
   - `RocksDbKvStoreTest`: put, get and scan round-trip;
   - `verifyModuleGraph` itself — a temporary disallowed edge fails the build, checked once by hand
     and described in the commit body.

**Done when:** `scripts/bench.sh quick --store rocksdb` runs, and `build` stays green.

### Step 5 — the experiments and the report (`m08/report`), ~4 days
1. Run E1–E7 on the `full` preset, 3 runs per point.
2. `docs(bench)`: `documentation/benchmarks/engine.md`:
   - the method and environment;
   - one Mermaid `xychart-beta` chart per experiment, each validated with mermaid-cli;
   - what each result means in RUM terms;
   - what surprised us;
   - for E6, the mechanisms behind the gap, each tied to a code path or an option (for example
     compression off, no block cache, a JVM heap vs native memory).
3. `build(ci)`: a CI job runs `scripts/bench.sh quick` with no thresholds (CI machines are noisy).

**Done when:** every experiment has a chart, its environment and its reproduction command, and two
`full` runs agree within the stated spread.

### Step 6 — the online checkpoint (`m08/checkpoint`), ~3 days
1. `docs(adr)`: ADR-0023, recording §2.6 and the rejected alternatives:
   - copying files under a write stall: it blocks writers for the length of a copy;
   - including the WAL instead of flushing: it is RocksDB's option for large memtables, and not
     worth a second code path at a 4 MiB write buffer;
   - an in-place directory with no rename: a crash leaves a directory that opens as empty.
2. `feat(env)`: `Env.link`, in `PosixEnv` and `FaultInjectionEnv`.
3. `feat(api)`: `Shale.checkpoint`, its metrics, and the `ReferenceBackend` equivalent (a copy of
   its map).
4. **Tests:**
   - **`CheckpointTest`:**
     - writes, then a checkpoint, then more writes: the checkpoint opens with exactly the first
       writes;
     - the source keeps working, and its compaction later deletes tables the checkpoint still
       holds links to, without affecting it;
     - an existing `target` → `IllegalArgumentException`;
     - a leftover `.checkpoint-tmp` is replaced;
     - a checkpoint during a running compaction (scheduler-controlled) is consistent.
   - **`ShaleCrashMatrixTest`** gains a checkpoint workload. A crash at every file operation of a
     checkpoint leaves:
     - the source reopening intact;
     - `target` either absent, or opening with exactly the pre-checkpoint contents.

**Done when:** green, including `crashTest`.

### Step 7 — the user guides (`m08/guides`), ~2 days
`docs(guides)`:
1. **`documentation/guides/operating-shale.md`**, the operator's companion to the embedding guide.
   - **Configuration:** every `ShaleOptions` field — what it trades, its default, and the
     experiment (E1–E4) that measured it.
   - **Sizing:**
     - memory: the write buffers, plus index and filter blocks per table;
     - disk: live data × the measured space amplification, plus a flush and a compaction's
       headroom;
     - file handles: one per table.
   - **Monitoring:** which metrics to watch, and what their values mean:
     - compaction debt and `level.<n>.*`;
     - write stalls;
     - `wal.sync.duration`;
     - the fsync group size.
   - **Backup and restore:** `checkpoint`, how to restore from one, and how to verify it.
   - **Failures:**
     - a `StorageException`, then the failed state: fix the environment, then reopen;
     - a `CorruptionException`: keep the directory, restore from a checkpoint, and file the
       offsets;
     - disk full.
   - **Upgrades:** the compatibility policy below.
2. **The compatibility policy**, stated in both guides.
   - **The API:** from 1.0, public types outside `dev.shale.internal` follow semantic versioning.
   - **On-disk formats:** every later version reads every earlier format, and never writes an
     old format.
   - **Downgrades:** a downgrade is not supported once a newer format has been written.
3. **`embedding-shale.md` and `faq.md`:** lift every limitation M5–M8 removed. Replace
   `0.0.1-SNAPSHOT` with `1.0.0`, and every "planned" performance answer with a number.

**Done when:** each guide's code snippets compile, pasted into a scratch project against the
published jar.

### Step 8 — the release and the tag (`m08/docs`), ~1 day
- The `shale-bench` README rewritten to what exists; a README "How it performs" section linking
  the report.
- A changelog entry with the three headline numbers; the completion plan's status table.
- **Version `1.0.0`:** set in `build.gradle.kts`.
- **The GitHub release `shale-1.0`:** attach `shale-core-1.0.0.jar` and its sources jar, and put
  the changelog entry in the notes. A user can then download the jar, or run
  `publishToMavenLocal` from the tag.
  - Publishing to Maven Central needs a Sonatype account and signing keys. That is the owner's
    decision, not part of this plan.
- **Reconciliation pass for D1.** Tag `shale-1.0`.

## 5. Milestone acceptance gates

- E1–E7 each have a chart, the environment and a reproduction command.
- A checkpoint is consistent, and crash-safe at every file operation (`crashTest`).
- The operating guide exists, and every snippet in both guides compiles against the released jar.
- Reproducible: two `full` runs agree within the spread the report states.
- No production module depends on `shale-bench` (`verifyModuleGraph`).
- The README's performance statements are numbers that link to the report.

## 6. Not in M8

YCSB workloads (out of scope); thresholds in CI; tuning the engine to the numbers (any
optimisation is a later `perf` commit with a `Benchmark:` trailer).

## References

RocksDB `db_bench` and the wiki "Benchmarking tools"; Athanassoulis et al., "The RUM Conjecture"
(EDBT 2016); Luo & Carey (VLDB J. 2020); Shipilëv, the JMH samples; Tene, HdrHistogram (the
design, not the library).
