# M5.5 — Concurrent write path: implementation plan

**Status:** planned; starts after M5 is tagged. **Depends on:** M5 (`Env`, `FaultInjectionEnv`,
`VersionSet`, `ShaleOptions`, the flush sequence of its plan §2.4, the failed state of §2.6).
**ADR:** 0014. **Unblocks:** M6 (compaction needs a background thread) and M7 (`writeAsync`
needs a write path that never blocks its caller on I/O). **Estimate:** 2–3 focused weeks, in 6
steps.

**Goal.** Stop serialising every writer behind an fsync.
- Writers hand their mutation to **one dedicated WAL-writer thread**. It batches whatever is queued
  into a group, appends the group to the WAL, syncs once if anyone in the group needs it, applies
  the group to the memtable, and completes each writer's future. That is group commit.
- Flush moves to **one background thread**, with a bounded number of immutable memtables. Writes
  stall when the bound is reached.

---

## 1. Where the code will be at the start (after M5)

| Fact | Where |
|---|---|
| `put`/`delete` take `writeLock` and do WAL append, sync, memtable insert and — when the memtable is full — the whole flush, all while holding it | `Shale.put`, `Shale.append`, `Shale.switchAndFlush` |
| The flush sequence is M5 plan §2.4, flush steps 1–6 | `Shale.switchAndFlush` |
| `Durability.GROUP` behaves exactly like `SYNC` | `Durability` Javadoc |
| The memtable is single-writer by design | ADR-0009, `SkiplistMemtable` |
| `ShaleOptions` exists with `writeBufferSizeBytes` and `manifestRolloverBytes` | M5 plan, Step 2 |

## 2. The design (decided — ADR-0014 records it with the alternatives)

### 2.1 One WAL-writer thread (amends ADR-0008's option B2)

ADR-0008 chose leader/follower batching (LevelDB's `DBImpl::Write`), where one of the waiting
writers becomes the leader and does the I/O for the group. This milestone uses a **dedicated
writer thread** instead (BadgerDB's `writeCh` design). ADR-0014 records why:

1. **Asynchronous submission.** M7's `writeAsync` and D4's commit protocol need a submission that
   never does I/O in the caller's thread. D4 calls it while holding its commit lock; under
   leader/follower, the caller could become the leader and fsync while holding that lock.
2. **A structural single writer.** Only the writer thread ever touches the WAL and the active
   memtable. The single-writer invariant of ADR-0009 becomes structural instead of a protocol
   that every leader must follow.
3. **The cost is a thread handoff per write** — tens of microseconds, measured in Step 6. It is
   small next to an fsync, and it is the price of (1).

**The queue and the loop:**
- **Submitting.** `WriteQueue.submit(WriteRequest)` appends to an unbounded-count, byte-bounded
  queue under a short lock and returns the request's `CompletableFuture<WriteResult>`. It returns
  *immediately*.
  - When the queued bytes exceed `writeQueueMaxBytes` (default 64 MiB), `submit` blocks until the
    queue drains below it. That is the only way a producer is ever held up.
- **Blocking writes.** `put` and `delete` are `submit(...).join()`, unwrapping the failure into
  the engine's exception types.
- **The thread** is named `shale-wal-writer` and loops. Each group:
  1. **Wait for work.** Wait until the queue is non-empty (or close is requested and it is
     empty — then exit).
  2. **Make room.** If the active memtable is at or above `writeBufferSizeBytes`, switch it (§2.3).
     This may stall (§2.4).
  3. **Form the group.** Take requests from the head until the group reaches `groupCommitMaxBytes`
     (default 1 MiB, RocksDB's cap). It always takes at least one.
  4. **Number it.** Assign consecutive sequence numbers across the group, in queue order.
  5. **Log it.** Append one WAL record per mutation (the WAL format is unchanged until M7).
  6. **Sync it.** If any request in the group has `SYNC` or `GROUP` durability, sync the segment
     once (`// DURABILITY:`). A group of only `NONE` requests does not sync.
  7. **Apply it.** Insert every mutation into the active memtable.
  8. **Publish it.** Set `visibleSequence` (a `volatile long`) to the group's last sequence.
  9. **Complete it.** Complete every request's future with its `WriteResult`: first and last
     sequence, WAL bytes, group size, whether the group synced.

**What this means for durability modes:**
- **`NONE`** requests in a syncing group wait for its sync, so a `NONE` write is never *less*
  durable than it asked for, but it may be delayed by a `SYNC` neighbour. The Javadoc says so.
- **`GROUP`** is identical to `SYNC` (both sync, both share syncs). ADR-0014 records that the
  distinction would only mean something with a deliberate commit delay (PostgreSQL's
  `commit_delay`), which is not planned. `Durability.GROUP`'s Javadoc is updated to say it is an
  alias kept for API stability.

### 2.2 Watermarks

- **`lastSequence`:** the last sequence number assigned; owned by the writer thread.
- **`visibleSequence`:** every entry with a sequence ≤ it is in a memtable. It advances only in
  group step 8, and groups apply in order, so it only moves forward.
- In M5.5 no reader filters by it; M7's snapshots and default reads read at `visibleSequence`.
  It exists now because this is the code that must maintain it.

### 2.3 Memtable switch and background flush

- **Switching** happens only on the writer thread (group step 2), under `viewLock`:
  1. freeze the active memtable and record its WAL segment number on it;
  2. close the old WAL segment — safe, because its last group has already completed;
  3. create the next segment (header, sync, sync the directory — M5 §2.4 flush step 1);
  4. publish a view with the new active memtable and the frozen one prepended to the immutables;
  5. submit a background task.
- **The background thread** is named `shale-background`: one platform thread, from an injected
  `Executor` (LevelDB's single background thread). One task runs at a time. A task does **one
  unit of work**: if there is an immutable memtable, flush the *oldest* (M5 flush steps 2–6);
  otherwise do nothing. M6 adds "else run one compaction if one is needed" to the same task.
- **The log number** in a flush's edit is the WAL segment number of the next newer memtable (an
  immutable, or the active one). Everything older than that segment is now in tables.
- **Publishing a flush:** after `logAndApply` succeeds, publish a view with that immutable removed
  and the new `Version`, under `viewLock`, in one step. Then signal the writer thread in case it
  is stalled.

### 2.4 Write stall

- **When:** at group step 2, if a switch is needed but there are already `maxImmutableMemtables`
  immutables (default 1, as in LevelDB).
- **What happens:** the writer thread waits on the condition `roomAvailable` (`viewLock` is a
  `ReentrantLock`) until a flush publishes.
  - **Metrics:** `write.stall.count` +1 per stall, and `write.stall.duration` (nanoseconds, from
    the `Clock`) when it ends.
  - **Visibility:** a `WARN` log line with the trigger, per `concurrency-and-resources.md` §6.
- **What producers see:** they keep submitting until the queue's byte bound is reached, then block
  in `submit`.
- **Not planned:** slowdown before stop. M6 adds level-0 triggers to the same wait.

### 2.5 Failures

- **The group always completes.** The writer thread wraps each group in `try`/`catch (Throwable)`.
  On any failure (an `IOException` from append or sync, or a test's `SimulatedCrash`):
  - it does **not** insert the group into the memtable, since the group was never acknowledged;
  - it completes every future in the group, and every request still queued, exceptionally with
    `EngineStateException("engine failed", cause)`;
  - it sets the engine's failed state (M5 §2.6) and exits.
- **After a failure:** `submit` fails fast with the same exception. There is no retry of a failed
  sync ("fsyncgate": after a failed fsync the page-cache state is unknown).
- **Background failures:** an `IOException` in a flush sets the failed state the same way, and
  signals `roomAvailable`, so a stalled writer thread wakes up, sees the failure, and fails its
  queue.
- **After a failure a reopen is required.** Unacknowledged groups whose bytes reached the WAL may
  reappear after recovery, as M5's policy already allows.

### 2.6 Close

In order:
1. Mark closed; `submit` now throws `EngineStateException("engine is closed")`.
2. The writer thread finishes every request queued *before* close, then exits. Those requests
   were accepted, so they must complete. `close()` joins it.
3. Wait for the background task that is *running* (not queued ones), then shut the executor
   down. Unflushed immutable memtables are safe: their WAL segments are still live, and the next
   open replays them.
4. Close files and release `LOCK`, as in M5.

## 3. New and changed types

| Type | Package | Visibility | Step |
|---|---|---|---|
| `WriteRequest` (record: mutations, durability, bytes, future) | `dev.shale` | package-private | 3 |
| `WriteResult` (record: firstSequence, lastSequence, walBytes, groupSize, synced) | `dev.shale` | package-private (public in M7) | 3 |
| `WriteQueue` (queue, byte bound, the writer thread loop) | `dev.shale` | package-private | 3 |
| `FlushJob` (M5 flush steps 2–6, moved out of `Shale`) | `dev.shale` | package-private | 4 |
| `ShaleOptions` | `dev.shale` | adds `maxImmutableMemtables`, `groupCommitMaxBytes`, `writeQueueMaxBytes` | 3, 4 |
| `Shale` | `dev.shale` | the package-private `open` takes an `Executor` for background work | 4 |
| `Durability` | `dev.shale` | Javadoc only: `GROUP` is an alias of `SYNC` | 3 |
| `ManualExecutor` (`runNext()`, `runAll()`, `pending()`) | `dev.shale` (test) | test | 4 |
| `FaultInjectionEnv` | `dev.shale.env` (`shale-core` test fixtures) | adds `holdSyncs()`, `releaseSyncs()`, `awaitSyncStarted()` | 3 |
| `RecordingMetrics` | `dev.shale` (test) | adds `awaitCounter(name, value)` and `awaitGauge(name, value)`, latch-based | 3 |

## 4. Steps

Each step is one branch off `main`, merged green before the next. `Milestone: M5.5` on every
`feat`/`fix`.

### Step 1 — ADR-0014 (`adr/0014-write-path`), ~1 day
`docs(api)`: ADR-0014 records §2. It amends ADR-0008's B2, and records the alternatives:
- LevelDB leader/follower (rejected: the caller can end up doing I/O — reason 1 of §2.1);
- a commit delay to give `GROUP` its own meaning (rejected: tuning with nothing to tune yet);
- several immutable memtables by default (rejected: LevelDB's one is enough at this scale; the
  option exists).

Update the ADR index. **Done when:** merged.

### Step 2 — the baseline (`m05-5/baseline-bench`), ~2 days
1. `build(bench)`: `shale-bench/build.gradle.kts` reads `-PjmhIncludes` and `-PjmhThreads` into
   the plugin's `includes` and `threads`, so one command runs one benchmark at one thread count.
2. `test(bench)`: `WritePathBenchmark` — `put` of 16-byte keys and 100-byte values:
   - sequential and random keys (`@Param`);
   - `SYNC` and `NONE` (`@Param`);
   - fresh database per trial;
   - `@Fork(2)`, `@Warmup(3)`, `@Measurement(5)`.
3. `test(bench)`: `ReadBenchmark` — `get` of random existing keys over 100,000 keys that have been
   flushed.
4. Run at 1, 4 and 16 threads. Record the numbers and the environment (commit, JDK, CPU, disk,
   filesystem) in this branch's merge commit body. Raw output is not committed.

**Done when:** the numbers are in the merge commit.

### Step 3 — the write queue (`m05-5/write-queue`), ~5 days
1. `test(api)`: the test helpers — `FaultInjectionEnv.holdSyncs/releaseSyncs/awaitSyncStarted`,
   and `RecordingMetrics.awaitCounter/awaitGauge`.
2. `feat(api)`: `WriteRequest`, `WriteResult`, `WriteQueue`, the writer thread, `visibleSequence`,
   and the new `ShaleOptions` fields. `put`/`delete` become submit-and-join. Metrics:
   - `wal.queue.depth` (gauge, on every submit and take);
   - `wal.group.size` (histogram);
   - `wal.sync.count` (existing).
3. `docs(api)`: `Durability.GROUP` Javadoc.
4. **Tests** (`ShaleWriteQueueTest`), each deterministic, with no sleeps:
   - **Group commit.** With syncs held, writer 1 `put`s (`SYNC`) on its own thread, then
     `awaitSyncStarted()`. Writers 2..N `put` on their own threads; `awaitGauge("wal.queue.depth",
     N−1)`. Release syncs. Assert: all N acknowledged; `wal.sync.count` increased by exactly 2;
     `wal.group.size` recorded 1 and N−1.
   - **No needless sync.** A group of only `NONE` requests does not sync.
   - **Order.** N concurrent writers: sequences are unique and gap-free, and the WAL holds them in
     sequence order.
   - **Visibility.** With syncs held, `visibleSequence` does not move and the group's keys are not
     readable; after release, both are.
   - **Fail-stop.** `failOn` the sync: every future in the group, and every queued one, fails with
     `EngineStateException`. A later `put` fails fast. No key of the failed group is readable.
   - **Close.** A request queued before `close()` completes; one after `close()` throws.

**Done when:** `ShaleWriteQueueTest` plus every existing test is green. Flush is still synchronous
— on the writer thread now.

### Step 4 — background flush and stalls (`m05-5/background-flush`), ~4 days
1. `test(api)`: `ManualExecutor`.
2. `feat(api)`: memtable switch per §2.3 (segment number recorded on the memtable); `FlushJob`;
   the background executor (production: a single-thread executor named `shale-background`;
   tests: `ManualExecutor`); `maxImmutableMemtables`; the stall (§2.4); background failure (§2.5);
   close (§2.6).
3. **Tests** (`ShaleBackgroundFlushTest`), each deterministic:
   - **Flush runs later.** A switch leaves one immutable and one queued task; the key reads from
     the immutable; `runNext()` flushes it to a table; the WAL segment is deleted; the key reads
     from the table.
   - **Log number.** After flushing the oldest of two immutables, the manifest's log number is
     the segment of the newer one; reopening replays only from there.
   - **Stall.** With `maxImmutableMemtables = 1` and one immutable pending, a writer's `put`
     (on its own thread) blocks. `awaitCounter("write.stall.count", 1)`; `runNext()`; the put
     completes. No write was acknowledged while stalled.
   - **Background failure.** `failOn` the flush's table sync: the engine enters the failed state,
     a stalled writer wakes and fails, and reads still serve.
   - **Close with a pending immutable.** Close without running the task, reopen: every write is
     present (replayed from the WAL).

**Done when:** these tests plus every existing test are green.

### Step 5 — the harnesses (`m05-5/concurrency-tests`), ~3 days
1. `test(memtable)`: `EngineModelTest` runs with `ManualExecutor`, `maxImmutableMemtables = 8`,
   and `runAll()` at seeded points at least every 4 operations. That makes stalls impossible in a
   single-threaded test, and the flush timing seeded.
2. `test(api)`: `ShaleConcurrencyTest` — 16 writers with mixed durability (seeded), 4 readers,
   the real executor. Every acknowledged value is readable afterwards; sequence numbers are gap-free.
3. `test(recovery)`: `ShaleCrashMatrixTest` gains a workload.
   - **Setup:** a group of 3 writers using the group-commit recipe.
   - **Run:** crash at every operation index, drop unsynced data, reopen.
   - **Assert:** every write whose `put` returned is present, and nothing unwritten appears.

**Done when:** all three are green; the crash matrix for 20 seeds locally.

### Step 6 — measurements, documentation, tag (`m05-5/docs`), ~2 days
1. Re-run Step 2's benchmarks. Put both tables (before and after) in the changelog entry, with
   the `wal.group.size` distribution at 16 threads. If 16-thread `SYNC` throughput did not
   improve, the entry says why.
2. `architecture/m5-5-concurrent-write-path.md`:
   - a sequence diagram of submit → group → sync → apply → complete;
   - the switch/flush/stall interplay;
   - the two watermarks;
   - the test map.
3. Glossary rows: write queue, write group, `visibleSequence`, write stall. Update the engine's
   `package-info` threading section: three threads — callers, `shale-wal-writer`,
   `shale-background`.
4. README status; the completion plan's status table.
5. `guides/embedding-shale.md`:
   - `GROUP` described as what it now is: a shared fsync;
   - the threading section's three threads;
   - write stalls, and what a caller sees during one;
   - the new `ShaleOptions` fields;
   - the "flush blocks writers" limitation removed.
6. **Reconciliation pass for M6:** reconcile its plan with the names that shipped.
7. Tag `m5.5-write-path`.

## 5. Milestone acceptance gates

- **Group commit is real:** the cohort gives 2 syncs for N writes (`ShaleWriteQueueTest`).
- **Nothing acknowledged is lost, nothing unacknowledged is visible:** the crash matrix's grouped
  workload, and the fail-stop test.
- **Order:** gap-free, unique sequences; `visibleSequence` is monotonic and never ahead of the
  memtable.
- **Stalls are bounded and observable:** the stall test, and `write.stall.*` metrics.
- **The caller never does I/O:** `submit` returns before the WAL is touched (asserted with syncs
  held).
- **Measured:** before and after numbers in the changelog.
- Every existing test stays green.

## 6. Not in M5.5

Compaction and level-0 stalls (M6); atomic multi-key batches and the public `writeAsync` (M7 —
this milestone's queue is what makes them possible); a commit delay; parallel memtable inserts.

## References

BadgerDB `db.go` (`writeCh`, `doWrites`, `writeRequests`); LevelDB `db/db_impl.cc`
(`DBImpl::Write`, `MakeRoomForWrite`, `BackgroundCall`) for the rejected alternative and the
background thread; RocksDB wiki "WAL Performance" and "Write Stalls"; ADR-0008, ADR-0009;
Rebello et al., "Can Applications Recover from fsync Failures?" (USENIX ATC 2020).
