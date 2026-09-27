# M8 — Engine benchmarks: Shale 1.0, measured: implementation plan

**Status:** planned; starts after M7 is tagged. **Ends the engine phase** with the tag `shale-1.0`.
**Depends on:**
- M6: the amplification and compaction-debt counters, both compaction styles;
- M7: bloom filters and `OperationStats`;
- M5.5: group commit.

**ADR:** none new — ADR-0013 already allows RocksDB as a benchmark-only baseline. **Estimate:** 2–3
focused weeks, in 6 steps.

**Goal.** Replace every performance claim about the engine with a measurement that has context.
Each experiment moves one storage knob and shows what it does to read, write and space cost — the
RUM tradeoff made concrete. RocksDB runs the same workloads so a reader can tell "slow" from "fine
for a hand-written engine". D7 later repeats the key experiments through SQL.

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
1. Run E1–E6 on the `full` preset, 3 runs per point.
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

### Step 6 — documentation and the tag (`m08/docs`), ~1 day
- The `shale-bench` README rewritten to what exists; a README "How it performs" section linking
  the report.
- A changelog entry with the three headline numbers; the completion plan's status table.
- **Reconciliation pass for D1.** Tag `shale-1.0`.

## 5. Milestone acceptance gates

- E1–E6 each have a chart, the environment and a reproduction command.
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
