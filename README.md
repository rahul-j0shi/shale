# ShaleDB

[![build](https://github.com/rahul-j0shi/shale/actions/workflows/build.yml/badge.svg)](https://github.com/rahul-j0shi/shale/actions/workflows/build.yml)

**A relational database built from scratch in Java, designed so the cost of every statement is
visible — from the SQL down to the disk.** An LSM storage engine, a SQL layer with a planner and
serializable transactions, and the PostgreSQL wire protocol, all hand-written with zero runtime
dependencies.

> **Status: the storage engine is built through M4; the database layers are planned.** What exists
> today is a durable, crash-consistent LSM write and read path. The table below is honest about the
> rest.

## Why

A database's real costs are hard to see, and hardest on LSM storage — the design under RocksDB,
CockroachDB, TiKV and Cassandra. There a write's cost is paid later by compaction, a delete leaves
a tombstone that slows future scans, and one `INSERT` into a table with two indexes becomes several
writes plus a uniqueness read.

Production systems expose fragments of this: PostgreSQL's buffer and WAL counts, CockroachDB's
MVCC step counts, RocksDB's per-operation counters. Each lives inside millions of lines of code, and
none follows one statement all the way from its plan to the compaction work it creates.

ShaleDB does, in a codebase small enough to read end to end. When it is finished, `psql` connects
to it and `EXPLAIN ANALYZE` reports each statement's plan together with its storage cost:
- memtable and SSTable probes;
- bloom-filter skips;
- blocks read;
- dead versions skipped;
- WAL bytes;
- the fsync it shared with other commits;
- its estimated compaction debt.

Cost can only be traced through layers you own, so every layer is written here. The full reasoning
is in the [charter](documentation/roadmap/charter.md).

## What exists today

| Component | State |
|---|---|
| WAL — LevelDB block log, CRC32C, torn-tail policy, replay recovery | ✅ M1 |
| Memtable — hand-written skiplist, lock-free readers, immutable handoff | ✅ M2 |
| SSTable — prefix-compressed blocks, restart points, index, versioned footer | ✅ M3 |
| Flush — fsync + atomic rename *before* the WAL segment is dropped | ✅ M3 |
| Reads — heap-based multi-way merge, reconciliation, tombstones, pinned tables | ✅ M4 |
| Manifest and safe file deletion · concurrent write path | ❌ M5 · M5.5 |
| Compaction · batches, snapshots, bloom filters · benchmarks | ❌ M6 · M7 · M8 |
| Record layer · SQL · joins · transactions | ❌ D1–D4 |
| PostgreSQL protocol · `EXPLAIN ANALYZE` cost accounting · demo | ❌ D5–D7 |

**How it is verified today.**
- A crash test truncates the WAL at every byte offset and asserts recovery yields a clean prefix.
- A bit-flip test at every byte offset asserts every SSTable corruption is detected.
- Golden files freeze the on-disk formats.
- A model harness runs thousands of random operations against a `TreeMap` oracle, restarting the
  engine mid-sequence.

`./gradlew build crashTest` is green on JDK 25 (161 tests).

## Use it

**Shale is a storage engine, and a storage engine is useful on its own.** It needs no query engine,
no server and no schema: it is a Java library that stores an ordered map of byte keys to byte
values in a directory, durably — what LevelDB and RocksDB are. Your program links it in and calls
`put`, `get`, `delete` and `scan`. A query engine is what a *database* adds on top; ShaleDB is that
database, and it is one consumer of the engine, not a requirement for using it.

| You want to… | Today | Guide |
|---|---|---|
| **Try the engine** in 30 seconds | `jshell` with the engine jar and [`scripts/try-shale.jsh`](scripts/try-shale.jsh) | [Embedding Shale §2](documentation/guides/embedding-shale.md#2-try-it-in-30-seconds-nothing-to-set-up-but-a-jdk) |
| **Store data from a Java (or Kotlin, Scala) application** | `./gradlew :shale-core:publishToMavenLocal`, then depend on `dev.shale:shale-core` | [Embedding Shale](documentation/guides/embedding-shale.md) |
| **Use SQL from any language** (`psql`, JDBC, Python) | not yet — ShaleDB's PostgreSQL protocol lands at D5 | planned: `documentation/guides/shaledb-quickstart.md` (D5) |
| **Read how it works, or ask why** | — | [architecture](documentation/architecture/README.md) · [ADRs](documentation/adr/README.md) · [FAQ](documentation/faq.md) |
| **Contribute** | the build below | [CONTRIBUTING.md](CONTRIBUTING.md) |

Try it now, with nothing installed but `git` and `curl`:

```bash
git clone https://github.com/rahul-j0shi/shale.git && cd shale
./scripts/bootstrap.sh && source scripts/env.sh      # a checksum-verified JDK 25 into .tools/
./gradlew :shale-core:jar
jshell --class-path shale-core/build/libs/shale-core-0.0.1-SNAPSHOT.jar scripts/try-shale.jsh
```

In your own code:

```java
try (Shale db = Shale.open(Path.of("data"), Clock.system(), Metrics.NOOP)) {
  db.put(key, value, Durability.SYNC);          // returns once the WAL is fsynced
  byte[] v = db.get(key);                       // null if absent
  try (Cursor c = db.scan(from, to)) { ... }    // [from, to), in bytewise key order
}
```

> **Not production software.** Shale is a study project with serious crash and corruption
> testing, but no production use. The guide's
> [limitations table](documentation/guides/embedding-shale.md#10-current-limitations--read-before-relying-on-it)
> says what is missing today and which milestone adds it — most importantly, until M6 there is no
> compaction, so disk use only grows.

## Architecture

```
psql · JDBC · demo app
      │ PostgreSQL wire protocol
shale-server  (D5)    sessions and protocol
shale-db      (D1–D6) record layer · SQL · planner · executor · transactions · cost accounting
      │ StorageBackend SPI
shale-core    (M0–M7) WAL · memtable · SSTables · manifest · compaction · bloom filters
```

`shale-core` depends on nothing but the JDK, and never on anything above it. The build checks
both. Scope diagrams: [`project-scope.md`](documentation/architecture/project-scope.md); as-built
designs per milestone: [`documentation/architecture/`](documentation/architecture/).

## Roadmap

Strictly ordered; each milestone ends in a tested, tagged artifact. **Shale 1.0** (the engine,
measured) is M8; **v1.0** (the database and demo) is D7. The
[completion plan](documentation/roadmap/completion-plan.md) has the per-milestone plans,
estimates and cut lines; the [changelog](CHANGELOG.md) has what shipped.

## Building from source

Target JDK **25**, vendored into the repository by the bootstrap script:

```bash
./scripts/bootstrap.sh && source scripts/env.sh
./gradlew build      # format, lint, compile, dependency check, fast tests
./gradlew crashTest  # crash-recovery suite
```

## References

Petrov, *Database Internals*; O'Neil et al., "The Log-Structured Merge-Tree" (1996); LevelDB and
the RocksDB wiki; Matsunobu et al., "MyRocks" (VLDB 2020); Graefe, "Volcano" (1994); Kung &
Robinson, "Optimistic Methods for Concurrency Control" (1981). Per-component citations live beside
each type, and every expensive decision is an [ADR](documentation/adr/README.md).
