# shale-bench — *harness wired, no benchmarks written yet*

The JMH plugin is applied and `shale-core` is on the benchmark classpath, so
`./gradlew :shale-bench:jmh` runs — it simply has nothing to run. Benchmark sources belong in
`src/jmh/java`.

**Why it is empty.** `commits.md` §4 rejects a `perf` commit without a `Benchmark:` trailer,
and nothing in the engine has been optimised yet: correctness first, measurement second,
optimisation third. There has been nothing worth measuring that a microbenchmark would answer.

**When that changes.** The first benchmarks arrive alongside compaction (M6), because
write/read/space amplification counters are what make the RUM tradeoff — the project's stated
thesis — measurable instead of asserted. M8 adds the YCSB A–F and db_bench-style macro
workloads and runs them across both backends.
