# M8 — Engine benchmarks: Shale 1.0, measured: implementation plan

**Status:** planned; starts after M7 is tagged. **Ends the engine phase** with the tag
`shale-1.0`. **Depends on:** M6's amplification and compaction-debt counters, M7's filters, and
M5.5's group commit — their options are the knobs being swept.

**Goal:** replace every performance claim about the engine with a measurement that has context.
Each experiment moves one storage knob and shows what it does to read, write and space cost — the
RUM tradeoff made concrete. RocksDB runs the same workloads as a reference, so a reader can tell
"slow" from "fine for a hand-written engine". D7 later repeats the key experiments through SQL.

## Scope

**Microbenchmarks (JMH, `shale-bench/src/jmh/java`):** varint and fixed-int coding, block seek,
skiplist insert and seek, a merge-iterator step, a bloom probe, CRC32C, and `OperationStats`
overhead. Each exists to support or refute a future `perf` commit.

**Workload harness** (hand-written in `shale-bench`, run through `scripts/bench.sh`):
db_bench-style `fillseq`, `fillrandom`, `overwrite`, `readrandom`, `readmissing`, `seekrandom`,
`readwhilewriting`, at stated key and value sizes. A small `KvStore` adapter lets the same driver
run against Shale and against RocksDB.

**Reference baseline:** RocksDB through its JNI bindings, configured to match — same memtable
size, same bloom bits, compression off, WAL on. The allowance is ADR-0013's, scoped to
`shale-bench`; this milestone adds the dependency and the Checkstyle suppression for `org.rocksdb`
in `shale-bench` only.

**Experiments, each a chart in the report:**
| # | Sweep | Shows |
|---|---|---|
| E1 | leveled vs size-tiered | write amplification against read and space amplification |
| E2 | bloom bits per key 0 / 5 / 10 / 15 | filter memory against `readmissing` latency |
| E3 | `SYNC` writers 1 / 4 / 16 / 64 | group commit: throughput and batch size |
| E4 | memtable size | write amplification and flush frequency |
| E5 | reads during compaction | p99/p999 of `readwhilewriting` over time, with compaction debt overlaid |
| E6 | Shale vs RocksDB | throughput and amplification on the same workloads, and why they differ |

## Rules

- Every run records commit, JDK, JVM flags, CPU, RAM, disk, filesystem and options, from a fixed
  seed, and runs each point at least 3 times. The report shows spread, not just a mean.
- Raw results are not committed (`commits.md` §3). The report commits the summarised numbers and
  charts as Mermaid `xychart-beta` blocks, so they render on GitHub and stay diffable.
- A result that contradicts the literature is reported as found, with the investigation.
- E6 explains differences, not excuses. RocksDB is expected to be faster; the report says by how
  much, and which mechanisms account for it.

## Task order (each task one commit, gate green)

1. Workload driver: key and value generators, operation mix, a log-bucket latency histogram
   (hand-written, after HdrHistogram's design), CSV output, environment capture.
2. db_bench-style workloads and `scripts/bench.sh` with a `quick` preset (CI smoke) and a `full`
   preset (the report).
3. JMH microbenchmarks.
4. The RocksDB adapter, dependency and scoped Checkstyle suppression.
5. Run E1–E6; write `documentation/benchmarks/engine.md`, covering the method, environment,
   charts, what each result means and what surprised us.
6. CI: a `quick` smoke job that checks the harness runs (no thresholds; CI hardware is noisy).
7. Docs: the `shale-bench` README; README "how it performs"; changelog; tag `shale-1.0`.

## Acceptance gates

- Every experiment has a chart, its environment and its reproduction command.
- Two `full` runs on the same machine agree within the spread the report states.
- No production module depends on `shale-bench`, checked by the build.

## References

RocksDB `db_bench` and its wiki ("Benchmarking tools", "Leveled Compaction"); Athanassoulis et
al., "The RUM Conjecture" (EDBT 2016); Luo & Carey (VLDB J. 2020); Shipilëv, the JMH samples;
Tene, HdrHistogram (the design, not the library).
