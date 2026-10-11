# Questions people ask

Answers to the questions two kinds of reader bring: **someone deciding whether to use Shale or
ShaleDB**, and **someone evaluating the project** — a reviewer or an interviewer. Every answer is
true of the code as it stands, or names the milestone that makes it true. When a milestone changes
an answer, it updates this page in the same change (`conventions/documentation.md`).

**Contents:** [the engine](#using-the-engine) · [the database](#using-shaledb) ·
[design and correctness](#design-and-correctness) · [the project itself](#the-project-itself)

---

## Using the engine

### Shale is "just a storage engine". Doesn't it need a query engine to be useful?

No. A storage engine is useful on its own, and most are used exactly that way. LevelDB sits under
Chrome's IndexedDB; RocksDB sits under Kafka Streams' state stores and Flink's keyed state; neither
has a query language. The application knows its keys and asks for them directly.

Shale is the same kind of thing: a Java library holding an ordered map of byte keys to byte
values in a directory. It recovers from process crashes; its known limits, including open
defects, are in [Embedding Shale §10](guides/embedding-shale.md#10-current-limitations--read-before-relying-on-it).
You call `put`, `get`, `delete` and `scan`.

A query engine is what turns that into a **database**: tables, SQL, a planner. ShaleDB is that
database, built on Shale. It is one user of the engine, not a requirement for using it. Start with
[Embedding Shale](guides/embedding-shale.md).

### What is it good for, then?

It suits anything that is naturally keyed and ordered:
- an application's local state;
- an index you maintain yourself;
- a cache that must survive restarts;
- an event log keyed by time;
- the storage layer of your own database, which is how ShaleDB uses it.

Ordered keys make prefix and range scans cheap. So design keys that put related data next to
each other: `user:42:order:0001`, not a random id.

### Can I use it from Python, Go or JavaScript?

**Not directly.** Shale is a JVM library, so it works from Java, Kotlin, Scala or Clojure.

From any other language, use **ShaleDB**: it speaks the PostgreSQL protocol (milestone D5), so
any PostgreSQL driver can connect. There is no C API, and none is planned.

### How do I install it?

There is no public Maven release yet. Build and install it locally:
- run `./gradlew :shale-core:publishToMavenLocal`;
- then depend on `dev.shale:shale-core:0.0.1-SNAPSHOT`.

[Embedding Shale §3](guides/embedding-shale.md#3-add-it-to-your-project) has Gradle and Maven
snippets. The engine has zero runtime dependencies, so it pulls nothing else into your build.
Jars are attached to the GitHub release from `shale-1.0` (M8).

### Can two processes open the same directory?

**No, and the engine stops it.** The first `open` holds the directory's `LOCK` file; a second
`open`, from another process or the same one, fails with `EngineStateException` ("database
directory is in use") and touches nothing. Before M5 nothing stopped it, and two engines would
have written the same log.

Within one process, share a single `Shale` instance across threads: it is thread-safe.

### How much memory and disk does it use?

**Memory:**
- the active memtable (the write buffer: 4 MiB by default, set at `open`);
- plus, while a flush runs, the memtable being flushed;
- plus the index block of every open SSTable.

There is no block cache (out of scope, ADR-0013). Reads of data blocks go through the operating
system's page cache.

**Disk:** today it **only grows**. Nothing reclaims overwritten or deleted data until compaction
arrives in M6; since M5 the manifest makes deleting a table safe, but nothing deletes one yet. After M6, leveled compaction bounds the space used to roughly 1.1× the live data
(the level-size ratio of 10). M8 measures the real figure.

### What happens if my machine loses power mid-write?

**An acknowledged `SYNC` write survives**, as it survives a process crash.
- **Why:** the record's bytes are fsynced before `put` returns, and every file name it depends on
  is synced too — a new log segment's, a flushed table's, `CURRENT`'s — before anything relies on
  it (M5; before that, a power loss could lose a whole file).
- **`NONE`:** the most recent writes may be lost. A segment is synced when it is closed at a
  memtable switch, so a `NONE` write in an older segment is never lost while a later `SYNC` write
  survives.
- **How it is tested:** a fault-injecting file system crashes six workloads at every file
  operation and then drops every unsynced byte and directory change a power loss may take. That
  simulates POSIX's guarantees; physical power loss is never tested, and rests on the disk
  honouring fsync.

After a process crash, recovery restores a **prefix** of your writes, never a later write without
an earlier one. A damaged log header is never mistaken for a torn tail: every fragment header
carries its own checksum, checked before recovery believes it (ADR-0024).
[Embedding Shale §5](guides/embedding-shale.md#5-durability-what-each-mode-promises) says which
failure each claim is tested against.

### How do I back it up?

- **Today:** close the engine, copy the directory, reopen.
- **From M8:** `Shale.checkpoint(Path)` makes a consistent copy while the engine keeps running.
  It hard-links the immutable SSTables, which is RocksDB's Checkpoint design.

A copy taken while the engine is writing is not guaranteed consistent before M8.

### Does it support transactions?

At the engine level:
- **Atomic multi-key writes** (`WriteBatch`) and **consistent snapshots** arrive in M7.
- **Read-modify-write transactions with conflict detection** belong to the database layer:
  ShaleDB's serializable transactions (D4) are built on the engine's batches and snapshots.

That is the same split as RocksDB's base API and its `TransactionDB`.

### Can I scan backwards, set a TTL, or compress values?

No, and none of these is planned:
- **Scans backwards:** cursors move forward only.
- **TTL:** there is none. Store an expiry time in the value and delete expired keys yourself.
- **Compression:** there is none. Compress values in your application if you need to.

### Is it production-ready?

**No.** It is a study project with no production use and no public release. Its known defects
are listed openly, in [Embedding Shale §10](guides/embedding-shale.md#10-current-limitations--read-before-relying-on-it)
and the [bug log](bug-log.md#open-defects), each with the milestone that fixes it. Two of them can
silently lose acknowledged writes. Use it to learn, to experiment, or to read.

---

## Using ShaleDB

*ShaleDB is planned (D1–D7). These answers describe the design its plans fix, and each names the
milestone that delivers it.*

### How will I connect to it?

With any PostgreSQL client, from D5:
- start the server;
- then run `psql -h localhost -p 5433` or use a JDBC URL.

`documentation/guides/shaledb-quickstart.md` (written in D5) gives the exact steps.

**Tested in CI:**
- `psql`;
- pgjdbc, the Java driver;
- psycopg, the Python driver: a smoke test.

Other drivers that use the standard protocol should work. **Tools that inspect `pg_catalog` will
not:**
- ORMs' schema introspection;
- `psql`'s `\d` commands.

`\d` returns `0A000` (feature not supported). D5 lists the protocol subset.

### What SQL does it support?

It supports a frozen subset, fixed so the scope cannot creep:
- **Statements:**
  - `CREATE`/`DROP TABLE` and `INDEX`;
  - `ALTER TABLE … ADD COLUMN`, for a nullable column;
  - `INSERT`, `SELECT`, `UPDATE`, `DELETE`.
- **Queries:**
  - inner and left joins;
  - `GROUP BY` with the standard aggregates;
  - `ORDER BY`, `LIMIT`, `OFFSET`;
  - `LIKE`, `IN` and `BETWEEN`;
  - `$n` parameters.
- **Transactions:** `BEGIN`/`COMMIT`/`ROLLBACK`.
- **Types:** `BIGINT`, `DOUBLE PRECISION`, `TEXT`, `BOOLEAN`.

`documentation/guides/shaledb-sql.md` (written in D2, extended in D3–D6) is the reference, and it
lists every difference from PostgreSQL.

### No `SERIAL`, no timestamps?

Both are deliberate.
- **Timestamps:** store them as `BIGINT` epoch milliseconds. They sort and compare correctly, and
  the demo does exactly this.
- **Ids:** generate them in the application (random 63-bit values, or UUIDs as `TEXT`).
  - **Why not a sequence:** under serializable optimistic concurrency, every `INSERT` would read
    and write one counter key, so any two concurrent inserts would conflict and one would retry.
  - **Where that design comes from:** CockroachDB recommends against sequences for the same reason.

### What isolation level do transactions get?

**Serializable**, always (D4).
- Transactions are optimistic: they run without locks and are validated at commit.
- **On a conflict**, `COMMIT` fails with SQLSTATE `40001`, and the application retries the
  transaction. That is the standard contract for serializable databases, PostgreSQL's included.
- **Checked in CI:** the classic anomalies (lost update, write skew, read skew) are tested against
  it.

### Can I see what a query cost?

Yes; that is the point of the project (D6). `EXPLAIN ANALYZE` reports, per plan operator, the
storage work done:
- memtable and SSTable probes;
- bloom-filter skips;
- blocks read;
- dead versions skipped;
- WAL bytes;
- the fsync shared with other commits;
- the compaction debt the statement added.

**And drawn, not only printed.** `EXPLAIN (ANALYZE, FORMAT JSON)` returns the same trace as JSON,
and the demo application (D7) draws it for every action:
- the statement's path, through the plan, the memtables and each LSM level, to the WAL, the
  shared fsync and the deferred compaction;
- the LSM with the statement's reads marked on it;
- a flame-graph-style breakdown of where the bytes went;
- a multi-statement transaction as one strip ending at `COMMIT`.

The plan half of the JSON also opens in PostgreSQL plan tools such as pev2.

The `shale_levels` and `shale_metrics` system tables show the engine's state live.

---

## Design and correctness

### Why an LSM tree and not a B-tree?

LSM trees turn random writes into sequential ones, and their costs are the most interesting to
make visible. The expensive work happens *later*:
- compaction rewrites data many times (write amplification);
- deletes leave tombstones that slow scans until compaction removes them;
- reads may probe several tables.

Those deferred costs are exactly what ShaleDB's `EXPLAIN ANALYZE` exposes, so the choice of an LSM
tree *is* the thesis ([charter](roadmap/charter.md)). It is also the design under RocksDB,
Cassandra, CockroachDB, TiKV and ScyllaDB.

### Why Java for a storage engine?

**Precedent.** The JVM is where much of the data infrastructure people run lives: Cassandra, HBase,
Kafka, Elasticsearch/Lucene, Neo4j and H2.

**The costs are measured, not hidden:**
- **Off-heap memory:** since JDK 22, the Foreign Function & Memory API makes it explicit and
  scoped, with no `Unsafe`.
- **Garbage-collector pauses:** the obvious objection. M8 measures them directly: p99.9 latency
  under G1 and ZGC, over a long mixed run (experiment E7).
- **Where the gap to RocksDB comes from:** M8 names which part of it is the JVM, and which part
  is missing features such as a block cache.

### How do you know it is correct?

By tests designed to find the bugs storage engines actually have. See the
[testing conventions](conventions/testing.md).

**Crash consistency:** the write-ahead log is cut at *every byte offset*, and recovery must return
exactly the complete records, and say how the file ended.

**Corruption:** every bit of every log header, and of small log files and SSTables, is flipped.
Each flip must be detected, or for SSTables proven harmless. Every decoder also meets malformed
structures whose checksums are valid.

**How the tests were found wanting, and fixed.** An external engineering review (October 2026)
showed that the tests covered less than the claims: the WAL had no bit-flip test, two different
one-bit header faults passed recovery silently, and several decoders trusted a length or a count
before checking it. M4.5 fixed them; the [bug log](bug-log.md) has each one, and the
[M4.5 case study](architecture/m4-5-integrity-boundaries.md) tells the story. Every other finding
has an owning plan step
([completion plan §11](roadmap/completion-plan.md#11-the-october-2026-engineering-review-where-each-finding-is-closed)).

**Model checking:** thousands of random operations, with restarts, run against a `TreeMap`
oracle.

**Format stability:** golden files freeze every on-disk format, so a byte change cannot slip in
unnoticed.

**Simulated power loss:** a fault-injecting file system crashes the engine at every file
operation of six workloads and drops what a power loss may take (M5). One random history also
runs under eight configurations that must agree (a metamorphic test).

**Planned:**
- **M6:** a soak test runs for hours.
- **D2:** SQL is checked in the sqllogictest format against PostgreSQL's semantics.
- **D4:** a checker tests serializability.
- **D5:** a real `kill -9` of the server process.

### What does it do when it finds corruption?

It is meant to stop and say where. A checksum mismatch throws `CorruptionException` with the
file, the offset, and the expected and actual values. Deciding whether to recover is
the operator's call, made explicitly. A storage engine that hides corruption is worse than one
that crashes.

A refused `open` leaves every file exactly as it was. The one case with a documented way out is a
WAL segment written before M4.5 whose last record cannot be verified:
[Embedding Shale §7](guides/embedding-shale.md#7-errors) says how an operator accepts that loss,
explicitly.

### How would you scale it beyond one machine?

Distribution is out of scope, deliberately: the project is about making one node's costs
visible (ADR-0013). But the seams a distributed design would use are there.

**Replication:** replicate a log of the engine's atomic `WriteBatch`es with Raft.
- Each replica applies the same batches in the same order.
- Each batch carries its sequence span (M7), so a replica's position is one number.
- That is how TiKV uses RocksDB underneath Raft, and how CockroachDB uses Pebble, its Go LSM
  engine modelled on RocksDB.

**Sharding:** partition the key space into ranges. ShaleDB's keys are already prefixed by table
and index id (D1), so a range split is a split in one ordered key space.

### How does it compare with RocksDB?

**Today, no claim is made.** M8 runs the same workloads on both and publishes the numbers. It also
names the mechanisms behind every gap, such as the missing block cache and compression, and the
JVM. The honest expectation is that RocksDB is faster: it has fifteen years of optimisation.

The comparison exists to tell "slow" from "reasonable for a hand-written engine", not to win.

---

## The project itself

### Why build all of it from scratch?

**The goal is to trace cost.** You can only trace a statement's cost through layers you own. A
SQL layer on top of RocksDB could not report which SSTables a query touched, or which fsync a
commit shared.

**Hand-writing is the rule** (CLAUDE.md N1): every core mechanism is written from first principles,
with its source cited beside the code. The [charter](roadmap/charter.md) has the full argument.

### How big is it?

At M4:
- about 3,300 lines of engine code;
- about 4,100 lines of tests, 161 in total.

The finished system is planned at a size one person can read end to end, and that is part of the
point.

### How is the work organised?

**Milestones:** strictly ordered. Each ends in a tested, tagged artifact, and each has an
implementation-level [plan](roadmap/completion-plan.md).

**Decision records:** every expensive or hard-to-reverse decision is an
[ADR](adr/README.md), written before the code, with the alternatives it rejected.

**As-built explanations:** every finished milestone has an [architecture](architecture/README.md)
page with diagrams.

**The bug log:** the [bug log](bug-log.md) records the defects the process caught, and how, plus
an open register of known defects not yet fixed.

### What was hardest, and what would you do differently?

This is answered honestly in the D7 retrospective, once there is a whole system to look back on.
Until then, the [bug log](bug-log.md) and the ADRs' "consequences" sections are the running
record.
