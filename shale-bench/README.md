# shale-bench — *harness wired, no benchmarks written yet*

The JMH plugin is applied and `shale-core` is on the benchmark classpath, so
`./gradlew :shale-bench:jmh` runs — it simply has nothing to run. Benchmark sources belong in
`src/jmh/java`.

**Why it is empty.** `commits.md` §4 rejects a `perf` commit without a `Benchmark:` trailer,
and nothing in the engine has been optimised yet: correctness first, measurement second,
optimisation third.

**When that changes.** A small baseline arrives before the write path changes (M5.5). The full
engine suite — db_bench-style workloads, the amplification counters, and RocksDB as a reference
baseline — is M8. The SQL-level workload, with SQLite as its reference, arrives with the demo (D7).
This is the only module allowed to depend on reference baselines, and no production module may
depend on it (ADR-0013).
