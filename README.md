# ShaleDB

[![build](https://github.com/rahul-j0shi/shale/actions/workflows/build.yml/badge.svg)](https://github.com/rahul-j0shi/shale/actions/workflows/build.yml)

**Shale is an LSM storage engine written from scratch in Java, as a study.** It is a LevelDB-style
engine with no runtime dependencies: a write-ahead log, a hand-written skiplist memtable,
SSTables and merged reads. It is built so that every mechanism can be read end to end and every
cost seen. **ShaleDB** is where it is going: a relational database on top of it, whose
`EXPLAIN ANALYZE` traces each statement's cost from the SQL down to the disk.

> **Status.**
> - **The engine is built through M4.** It recovers from process crashes; power-loss durability
>   arrives at M5.
> - **Its known defects are listed openly.** Two of them let a one-bit fault in a WAL header
>   silently lose acknowledged writes. The [open-defect register](documentation/bug-log.md#open-defects)
>   names each, with the milestone that fixes it. **M4.5, next, fixes the recovery path.**
> - **SQL, transactions and the PostgreSQL protocol (D1–D7) are planned, not built.**

## What exists today

| Component | State |
|---|---|
| WAL — LevelDB block log, CRC32C per fragment, torn-tail policy, replay recovery | ✅ M1 · ⚠️ its header fields are unprotected (SH-08, SH-18) |
| Memtable — hand-written skiplist, lock-free readers, immutable handoff | ✅ M2 |
| SSTable — prefix-compressed blocks, restart points, index, versioned footer | ✅ M3 · ⚠️ some structural fields are trusted unchecked (SH-14 to SH-16) |
| Flush — fsync and atomic rename *before* the WAL segment is dropped (no directory sync yet) | ✅ M3 |
| Reads — heap-based multi-way merge, reconciliation, tombstones, pinned tables; cursors are weakly consistent | ✅ M4 |
| Integrity boundaries — a checksum on every log header, verified tails, decoder validation | ⏭ M4.5 |
| Manifest, safe file deletion, directory syncs, failed state · concurrent write path | ❌ M5 · M5.5 |
| Compaction · batches, snapshots, bloom filters · benchmarks | ❌ M6 · M7 · M8 |
| Record layer · SQL · joins · transactions | ❌ D1–D4 |
| PostgreSQL protocol · `EXPLAIN ANALYZE` cost accounting · demo | ❌ D5–D7 |

**How it is verified today, and against what.** Each test covers one failure model, and no test
stands in for another (ADR-0024 §7):
- **Torn tail:** a crash test truncates the WAL at every byte offset and asserts that recovery
  yields exactly the complete records.
- **Malformed bytes:** a bit-flip test changes every byte of an SSTable and asserts each change
  is detected or provably harmless. The WAL has no such test yet; M4.5 adds one at every bit.
- **Format stability:** golden files freeze the on-disk formats.
- **Clean restart:** a model harness runs thousands of random operations against a `TreeMap`
  oracle, restarting the engine mid-sequence.
- **Power loss:** not tested yet. M5 simulates it at every file operation.

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

Try it now, with nothing installed but `git` and `curl` (Linux or macOS, x64 or ARM):

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

> **Not production software.** Shale is a study project with no production use, and with known
> defects listed openly. The guide's
> [limitations table](documentation/guides/embedding-shale.md#10-current-limitations--read-before-relying-on-it)
> says what is missing or broken today and which milestone fixes it. Most importantly: until M4.5,
> one flipped bit in a WAL header can silently lose acknowledged writes; until M5, `SYNC` is not
> power-loss durable; until M6 there is no compaction, so disk use only grows.

## Why: ShaleDB, a see-through database

A database's real costs are hard to see, and hardest on LSM storage: the design under RocksDB,
Cassandra, TiKV, and CockroachDB's Pebble.
- A write's cost is paid later, by compaction.
- A delete leaves a tombstone that slows future scans.
- One `INSERT` into a table with two indexes becomes several writes plus a uniqueness read.

Production systems expose fragments of this: PostgreSQL's buffer and WAL counts, CockroachDB's
MVCC step counts, RocksDB's per-operation counters. Each lives inside a codebase of millions of
lines, and none follows one statement all the way from its plan to the compaction work it
creates.

ShaleDB is meant to, in a codebase small enough to read end to end. When it is finished, `psql`
connects to it and `EXPLAIN ANALYZE` reports each statement's plan together with its storage cost:
- memtable and SSTable probes;
- bloom-filter skips;
- blocks read;
- dead versions skipped;
- WAL bytes;
- the fsync it shared with other commits;
- its estimated compaction debt, which is labelled an estimate, because deferred work is shared by
  many statements.

The same numbers are also **drawn**. In the demo application, every action opens a trace view
showing the statement's path from the SQL through the plan, the memtables and each LSM level, to
the WAL, the fsync it shared and the compaction it defers. Which levels were read, and which a key
range or a bloom filter ruled out, is visible at a glance; the deferred part is drawn dashed,
because it is an estimate. Text and picture come from one trace, so they cannot disagree.

Existing systems can be instrumented from the outside, but owning every layer is what makes the
attribution exact and short enough to explain, and building each layer is how it is learned.
What a reader sees at v1.0, and how each part is checked, is in the
[charter](documentation/roadmap/charter.md#5-the-end-goal-what-someone-sees-at-v10).

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

Strictly ordered; each milestone ends in a tested, tagged artifact.
- **M4.5 is next.** It responds to an
  [external engineering review](documentation/roadmap/completion-plan.md#11-the-october-2026-engineering-review-where-each-finding-is-closed)
  (October 2026): the log gets a header checksum, recovery classifies a tail only from verified
  bytes, and every decoder validates its fields
  ([ADR-0024](documentation/adr/0024-recovery-integrity-boundaries.md)).
- **Shale 1.0** (the engine, measured) is M8; **v1.0** (the database and demo) is D7.

The [completion plan](documentation/roadmap/completion-plan.md) has the per-milestone plans,
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
