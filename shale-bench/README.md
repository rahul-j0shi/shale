# shale-bench — *harness wired, no benchmarks written yet*

The JMH plugin is applied and `shale-core` is on the benchmark classpath, so
`./gradlew :shale-bench:jmh` runs — it simply has nothing to run. Benchmark sources belong in
`src/jmh/java`.

**Why it is empty.** `commits.md` §4 rejects a `perf` commit without a `Benchmark:` trailer,
and nothing in the engine has been optimised yet: correctness first, measurement second,
optimisation third.

**When that changes.** A small baseline arrives before the write path changes, as M5.5's first
benchmark step. It covers write and read throughput, flush-tail latency, and the read fan-out of
an empty scan and a missing key over 1, 16 and 128 tables, the measurement the October 2026 review
asked for (SH-07, SH-17). Its results are committed as `documentation/benchmarks/0001-…`, so M6's
range selection can be checked against them. The full
engine suite — db_bench-style workloads, the amplification counters, and RocksDB as a reference
baseline — is M8. The SQL-level workload, with SQLite as its reference, arrives with the demo (D7).
This is the only module allowed to depend on reference baselines, and no production module may
depend on it (ADR-0013).
