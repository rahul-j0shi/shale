# M5.5 — Concurrent write path: implementation plan

**Status:** planned 2026-09-26; starts after M5 is tagged. **Depends on:** M5 (flush installs a
Version through the manifest; WAL segments are reclaimed only after a durable edit).
**Unblocks:** M6 — compaction needs a background executor and a write path that does not hold
the lock across an fsync.

**Goal:** stop serialising every writer behind an fsync. Assign sequence numbers and append to
the WAL under a short critical section, force outside it, batch concurrent writers into one
force (group commit, finally honouring `Durability.GROUP`), and move flush to an injected
background executor with a bounded immutable-memtable queue and write stalls.

## Step 0 — benchmark baseline (before any change)

The 2026-09-10 assessment requires a baseline before the write path moves. In `shale-bench`,
add JMH benchmarks for: `fillseq` and `fillrandom` at `SYNC` and `NONE` with 1, 4 and 16 threads,
`readrandom` over a flushed dataset, and reads during a flush. Record commit, JDK, CPU, disk,
filesystem and configuration in the M5.5 release note. Raw JMH output is not committed
(`commits.md` §3). These numbers become the "before" in every `Benchmark:` trailer.

## Decisions required in the ADR

ADR-0008 already chose leader/follower batching (B2). This ADR fixes the mechanics:

1. **Writer queue.** Recommended: LevelDB's `DBImpl::Write`. Writers enqueue; the front writer
   becomes leader, merges queued requests up to a byte cap (RocksDB uses 1 MiB), assigns their
   sequence numbers, appends one WAL write, releases the lock for `force()` and the memtable
   inserts, then wakes the followers. The memtable stays single-writer (ADR-0009): only the
   leader inserts.
2. **Watermarks.** Define *assigned*, *durable* and *visible* sequence numbers. Visible advances
   only after every entry at or below it is in the memtable. M7a's snapshots read at the visible
   watermark, so it is defined now even though `get` does not filter by it yet.
3. **Mixed durability.** A group containing any `SYNC`/`GROUP` writer forces. `NONE` writers in
   a forced group get the stronger guarantee for free; they are never delayed to wait for one.
4. **Force failure is fail-stop.** After a failed `force()`, the page cache state is unknown
   (PostgreSQL "fsyncgate", 2018). The engine enters a terminal failed state: every waiting writer
   gets `StorageException`, and later calls get `EngineStateException`. No retry of the fsync.
5. **Background flush.** Flush runs on an injected `Executor`; tests inject a deterministic one
   that runs tasks on demand (`testing.md` §2). The immutable-memtable queue is bounded
   (default 2, like RocksDB's `max_write_buffer_number`).
6. **Write stall.** When the queue is full, writers wait on the lock's condition until a flush
   installs. Count stall time in `write.stall.micros`. Slowdown before the hard stop is a stretch.
7. **Rotation during force.** A memtable switch may happen while a force is in flight; the old
   segment is closed only after its last force completes, and deleted only after the flush's
   Version edit is durable (M5 rule).
8. **Close.** `close()` stops admitting writes, drains the queue and the flush executor, then
   closes files. It is idempotent; later calls throw `EngineStateException`.

## Scope

**In M5.5:** the writer queue and group commit; watermarks; background flush with the bounded
queue and stalls; fail-stop semantics; draining close; metrics `wal.group.size`,
`wal.force.count`, `write.stall.micros`, `flush.queue.depth`.

**Deferred:** compaction and L0-count stalls (M6); atomic multi-key batches in the API (M7a —
the internal group is not a user-visible batch); pipelined or parallel memtable writes.

## Task order (TDD; each task one commit, gate green)

1. ADR, this plan, ADR index. Benchmark baseline (step 0) as a `build(bench)` + `test(bench)` pair.
2. Watermarks introduced with the existing single-lock path (a refactor, behaviour unchanged).
3. Writer queue with leader election. Group commit is proven with a controlled cohort: N writers
   are parked at a barrier through the M5 file-operation seam, released together, and the test
   asserts one force for N acknowledged writes.
4. Fail-stop: an injected force failure fails the whole group and every later call.
5. Flush executor seam and the deterministic test executor; move flush off the write path.
6. Bounded immutable queue and write stall; a stall test with the deterministic executor.
7. Draining, idempotent `close()`; operations-after-close tests.
8. Extend the model harness to run flushes on the deterministic executor at seeded points;
   extend `ShaleConcurrencyTest` for 16 writers with mixed durability.
9. Benchmarks after the change; numbers into the release note with the baseline.
10. Docs: engine `package-info`, the `architecture/m5-5-concurrent-write-path.md` as-built page
    (writer-queue sequence diagram, watermark diagram), glossary, README status, release note.

## Acceptance gates

- **Batching works:** a controlled cohort of N writers produces exactly one force.
- **Nothing acknowledged is lost:** at every crash point in the file-operation trace of a
  grouped write, recovery keeps every write acknowledged under `SYNC`/`GROUP`.
- **Order holds:** sequence numbers stay unique and gap-free; the visible watermark never
  exposes an entry before every lower-numbered entry is in the memtable.
- **Fail-stop:** after an injected force failure, no later write is acknowledged.
- **Stalls are bounded:** with flush paused, writers block at the queue bound and resume after
  one flush; no writer is acknowledged while blocked.
- **Measured:** 16-thread `SYNC` throughput exceeds the step-0 baseline, reported with the
  batch-size histogram. If it does not, the release note says why.
- Existing model, crash, format and concurrency tests stay green.

## References

LevelDB `db/db_impl.cc` (`DBImpl::Write`, `BuildBatchGroup`); RocksDB wiki "WAL Performance" and
"Write Stalls"; ADR-0008; Petrov, *Database Internals* ch. 5; the PostgreSQL fsync-error handling
discussion (2018).
