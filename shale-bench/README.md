# shale-bench — *harness wired, no benchmarks written yet*

The JMH plugin is applied and `shale-core` is on the benchmark classpath, so
`./gradlew :shale-bench:jmh` runs — it simply has nothing to run. Benchmark sources belong in
`src/jmh/java`.

**Why it is empty.** `commits.md` §4 rejects a `perf` commit without a `Benchmark:` trailer,
and nothing in the engine has been optimised yet: correctness first, measurement second,
optimisation third. There has been nothing worth measuring that a microbenchmark would answer.

**When that changes.** A small durable-write/read-during-flush baseline arrives after M5 and
before M5.5's concurrency changes; see the [M5.5 plan](../documentation/roadmap/m5.5-concurrent-write-path.md).
M6 adds write/read/space amplification measurements. M8 adds YCSB A–F and db_bench-style
macro workloads and the backend comparison.
