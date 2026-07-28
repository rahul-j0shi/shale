# 0008. Durability modes, group commit, and an injected clock

- **Status:** Accepted
- **Date:** 2026-07-28
- **Milestone:** M1
- **Reversible:** partly — the `Durability` enum and the ordering invariant are hard to change (they are the engine's correctness contract, D4); the group-commit *mechanism* and the clock plumbing are freely revisable.

## Context

M1 makes the engine durable. Concurrency-and-resources.md §5 already fixes the shape:
every acknowledging write takes an explicit `Durability` (D1), the exact line where data
becomes durable is marked `// DURABILITY:` (D2), the WAL record is durable before the
memtable is updated (D3), and every durability claim has a crash test (D5). This ADR pins
the *semantics* of the three modes, how group commit batches an `fsync`, and how time is
sourced — because measuring `fsync` latency (a required metric) needs a clock, and
`System.nanoTime()` is banned from direct use (java-style.md §7, testing.md §2).

## Options considered

### Durability — what each mode promises
- **A1 — one mode (always fsync).** Simplest and safest, but throws away the throughput
  knob the whole exercise is about; RocksDB defaults to *not* syncing for a reason.
- **A2 — three modes: NONE / SYNC / GROUP.** The caller chooses per write. Matches the M0
  API already in `StorageBackend`.

### Group commit — how concurrent syncs share one fsync
- **B1 — no batching.** Every `SYNC` write calls `force()` itself. Correct, but N concurrent
  writers pay N fsyncs.
- **B2 — leader/follower batching.** Writers append their record under a short lock, then
  exactly one becomes the *leader* and issues a single `force()` that covers every record
  appended before it; followers wait on a condition and return once the leader's force
  completes. One fsync amortised over the batch.

### Clock
- **C1 — call `System.nanoTime()` at the site.** Banned, and untestable deterministically.
- **C2 — inject a `Clock` abstraction** (`nanoTime()` monotonic + `epochMillis()` wall), with
  a system implementation and a manual one tests advance explicitly.

## Decision

**A2 + B2 + C2.**

- `Durability.NONE` — append to the OS page cache (`write`), acknowledge. Survives a process
  crash, not power loss.
- `Durability.SYNC` — append, then `FileChannel.force(false)` before acknowledging. Survives
  power loss. The `force` call carries the `// DURABILITY:` comment.
- `Durability.GROUP` — same guarantee as `SYNC`, but concurrent writers are coalesced into one
  `force()` via leader/follower batching (B2). Acknowledged only after the shared force that
  covers the record returns.
- **Ordering (D3):** append-to-WAL (and `force`, for SYNC/GROUP) happens **before** the
  memtable is updated, which happens before acknowledgement. A crash before the `force` loses
  only writes that were never acknowledged.
- **No default.** The mode is always an explicit argument; there is no overload that silently
  picks `NONE`.
- **Clock:** `dev.shale.Clock` is injected everywhere time is read. `SystemClock` is the one
  place `System.nanoTime()` is called (with a suppression noting why); tests use a
  `ManualClock`. All `wal.*.duration` metrics are measured through it.

Group commit is guarded by the WAL's own lock; no lock is held across the `force()` for
followers (they wait on a condition, not the I/O). Batching is opportunistic — whatever is
already appended when the leader starts its force is covered; the writer never delays to grow
a batch (matching RocksDB's documented behaviour).

## Rationale

Three modes keep the read/write/space knob explicit and testable, which is the project's
whole premise. Leader/follower batching is the standard group-commit shape and turns the
fixed cost of `fsync` from per-write into per-batch without ever acknowledging before the
force that covers the record — so the durability guarantee is never weakened for speed
(D4). Injecting the clock is what makes the crash and latency tests deterministic and is the
on-ramp to simulation testing at M9 (testing.md §2).

## Consequences

**Positive:** an explicit, testable durability knob; one amortised fsync under concurrency;
deterministic time in tests; a single audited `System.nanoTime()` call site.

**Negative:** group commit adds real concurrency (a lock, a condition, leader election among
writers) and therefore real concurrency tests — the hardest code in M1. The `Clock` seam
threads an extra constructor parameter through everything that measures or waits.

**Neutral:** `NONE` remaining non-crash-safe is a deliberate, documented trade-off, not a
bug; the Javadoc states exactly what each mode survives.

**If we need to reverse this:** changing a mode's guarantee (e.g. making `NONE` sync) is a
breaking change needing its own ADR and a `Reversible: no` trailer (D4). Swapping the
group-commit mechanism or the clock implementation is internal and free.

## References

- `documentation/conventions/concurrency-and-resources.md` §5 (D1–D5)
- RocksDB "WAL Performance" wiki (group commit, 1 MB cap, no proactive delay)
- Ongaro notes on batching; PostgreSQL `commit_delay`/`commit_siblings`
- [[0007-wal-block-log-format]]
