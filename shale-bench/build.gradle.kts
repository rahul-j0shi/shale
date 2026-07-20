// shale-bench — JMH microbenchmarks and YCSB/db_bench-style harnesses for the engine.
// Benchmarks are committed before the optimisations they justify (CLAUDE.md §5, the
// `perf` commit rule). Benchmark sources live in src/jmh/java.

plugins {
    id("me.champeau.jmh") version "0.7.2"
}

jmh {
    jmhVersion = libs.versions.jmh
}

dependencies {
    jmh(project(":shale-core"))
}
