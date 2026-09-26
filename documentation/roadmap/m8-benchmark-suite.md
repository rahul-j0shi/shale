# M8 — Benchmark suite: the engine, measured: implementation plan

**Status:** planned 2026-09-26; starts after M7c is tagged (or after D6 on the fast-track).
**Ends P1** with the tag `shale-1.0`. **Depends on:** M6's amplification counters, M7's filters
and cache (their options are the knobs being swept), M5.5's group commit.

**Goal:** replace every performance claim in the project with a measurement. The charter's thesis
is the RUM conjecture; this milestone measures it within the LSM by sweeping the knobs that move
read, update and memory overhead against each other. The COW B+Tree comparison that the charter
put here is now the optional M8b (ADR-0013); this suite is what M8b will reuse.

**Split from the charter's M8:** the charter's M8 was "COW B+Tree + full benchmark suite". The
Sept 7 critique already said "ship the harness and the LSM numbers regardless". This plan makes
that the milestone, and moves the tree to M8b.

## Scope

**Microbenchmarks (JMH, `shale-bench/src/jmh/java`):** varint and fixed-int coding, block
seek, skiplist insert and seek, merge-iterator step, bloom probe, CRC32C. Each exists to support
or refute a future `perf` commit.

**Macro harness (a hand-written driver in `shale-bench`, run through `scripts/bench.sh`):**
- **db_bench-style:** `fillseq`, `fillrandom`, `overwrite`, `readrandom`, `readmissing`,
  `seekrandom`, `readwhilewriting`, at stated key and value sizes.
- **YCSB A–F:** workloads re-implemented from the YCSB paper's definitions, with a hand-written
  scrambled-zipfian generator (Gray et al., 1994). The YCSB tool is not added as a dependency;
  the workload definitions are the specification.

**Experiments (the RUM story), each a chart in the report:**
| # | Sweep | Shows |
|---|---|---|
| E1 | leveled vs size-tiered | write amplification vs read and space amplification |
| E2 | bloom bits per key 0 / 2 / 5 / 10 / 15 | memory vs `readmissing` latency |
| E3 | block cache size | memory vs `readrandom` p50/p99 |
| E4 | `SYNC` writers 1 / 4 / 16 / 64 | group commit: throughput and batch size |
| E5 | memtable size | write amplification and flush frequency |
| E6 | tail latency during compaction | p99/p999 of `readwhilewriting` over time |

## Rules

- Every run records commit, JDK, JVM flags, CPU, RAM, disk, filesystem and options, from a fixed
  seed, and runs each point at least 3 times; the report shows spread, not just a mean.
- Raw results are not committed (`commits.md` §3). The report commits the summarised numbers and
  charts as Mermaid `xychart-beta` blocks in Markdown, so they render on GitHub and stay diffable.
- A result that contradicts the literature is reported as found, with the investigation.

## Task order (each task one commit, gate green)

1. Workload driver: key/value generators, operation mix, latency histogram (hand-written HDR-style
   log-bucket histogram), CSV output, environment capture.
2. db_bench-style workloads; `scripts/bench.sh` with named presets (`quick` for CI smoke,
   `full` for the report).
3. YCSB A–F workloads and the zipfian generator (property test on its distribution).
4. JMH microbenchmarks.
5. Run E1–E6; write `documentation/benchmarks/shale-1.0.md` (method, environment, charts, what
   each result means, what surprised us).
6. CI: a `quick` preset smoke job that checks the harness runs (no thresholds; CI hardware is
   noisy).
7. Docs: `shale-bench` README rewritten from "harness wired, nothing written" to what exists;
   README status; release note; tag `shale-1.0`.

## Acceptance gates

- Every experiment E1–E6 has a chart, its environment and its reproduction command.
- Numbers are reproducible: two runs of the `full` preset on the same machine agree within the
  spread the report states.
- The README's "how it is verified" and a new "how it performs" section cite this report, not
  adjectives.

## References

Cooper et al., "Benchmarking Cloud Serving Systems with YCSB" (SoCC 2010); RocksDB `db_bench`
and its wiki "Benchmarking tools"; Athanassoulis et al., "Designing Access Methods: The RUM
Conjecture" (EDBT 2016); Gray et al., "Quickly Generating Billion-Record Synthetic Databases"
(SIGMOD 1994); Shipilëv, "JMH samples"; Tene, HdrHistogram (the design, not the library).
