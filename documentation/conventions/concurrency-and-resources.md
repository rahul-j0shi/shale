# Concurrency, resources, and durability

Three classes of bug dominate storage engines: a data race that corrupts a structure, a
resource leak or use-after-free that crashes the JVM, and a durability gap that loses an
acknowledged write. All three are hard to reproduce and easy to introduce. These rules
exist to make them structurally difficult rather than merely discouraged.

---

## 1. Every type declares its threading contract

No exceptions. A class is annotated at the type level with one of:

```java
@ThreadSafe        // safe for concurrent use by any thread
@NotThreadSafe     // single-threaded; Javadoc names the owning thread or role
@Immutable         // no mutable state reachable after construction
```

Define these annotations yourself in `dev.shale.internal.annotations` — three
`@Documented @Target(TYPE)` marker interfaces, about fifteen lines total. They are
documentation with a compiler-visible name, and writing them costs less than arguing
about which third-party jsr305 variant to depend on.

**Every mutable field is one of:**
- `final` (preferred — most fields should be),
- `volatile`, with a comment stating the publication it provides,
- `@GuardedBy("lockName")`, naming the exact monitor,
- an `Atomic*` or a concurrent collection.

A bare non-final field with no annotation is a review failure. "It's only written from
one thread" is precisely the claim the annotation exists to record.

---

## 2. Locking

- Lock objects are `private final` and named for what they protect:
  `versionLock`, not `lock` or `mutex`.
- Never `synchronized` on `this` or on a public field — an outside caller can then
  participate in your lock and you will not find out until it deadlocks.
- Never `synchronized` on a public method, for the same reason.
- Document lock ordering in the owning package's `package-info.java` and acquire in
  that order globally. The engine has a natural hierarchy —
  `dbLock > versionLock > memtableLock` — and every deadlock in this codebase will
  come from violating it.
- **Never hold a lock across I/O.** The whole point of the LSM design is that the write
  path is short; blocking a memtable insert on an fsync serialises everything. Copy what
  you need under the lock, release, then do the work.
- Prefer immutable snapshots over locks. The `Version` type is the canonical example:
  readers grab the current `Version` reference (one volatile read), and compaction
  installs a new one atomically. Readers never block on compaction.

---

## 3. Threads

- Every thread and executor has a descriptive name: `shale-flush-0`,
  `shale-compact-2`, `flotilla-raft-tick`. An unnamed thread in a stack dump at 3am
  is a wasted hour.
- No unbounded thread pools. Compaction and flush pools are explicitly sized and that
  size is a documented configuration knob with a stated default and rationale.
- Every background thread has an uncaught exception handler that marks the database
  as failed. **A background compaction that dies silently leaves the engine in a state
  where reads still work and space grows without bound** — the worst kind of failure,
  because it looks healthy. Once the engine is marked failed, all writes reject.
- Shutdown is explicit and ordered: stop accepting writes, drain and stop background
  work with a timeout, flush, close files, close arenas. Never rely on shutdown hooks
  or daemon threads for correctness.

---

## 4. Resource ownership

Every resource has exactly one owner and a documented lifetime. Write it in the Javadoc
under **Ownership** (see `java-style.md` §5).

**Arenas.** All off-heap memory comes from an `Arena`. The arena's scope is stated where
it is created and is always at least as long as any `MemorySegment` derived from it.
Prefer `Arena.ofConfined()` (one thread, deterministic close) and escalate to
`ofShared()` only when a segment genuinely crosses threads — and say why in a comment.

Never return a `MemorySegment` from a method whose arena closes before the caller is
done. This does not throw a nice exception; on a mapped segment it can crash the JVM.
When in doubt, copy.

**Files.** Every open file is either inside a try-with-resources or held by a type that
is itself `AutoCloseable` and closed by its owner. `SSTable` handles are
reference-counted:

```java
SSTable table = version.pick(key);   // returns retained
try {
  ...
} finally {
  table.release();                   // always paired, always in finally
}
```

`release()` reaching zero deletes the file if it has been removed from the live version.
This is what lets compaction delete inputs while a long-running iterator still reads
them. Getting it wrong deletes a file out from under a reader.

**Leak detection.** In test builds, register every arena and file handle with a tracker
that fails the test suite if anything is outstanding at teardown, and use a `Cleaner`
to log a loud warning with the allocation stack trace. The `Cleaner` is a *detector*,
never the mechanism — correctness never depends on GC timing.

---

## 5. Durability

This is the rule set that distinguishes a storage engine from a cache.

**D1 — Acknowledgement implies a stated guarantee.** Every method that can return
success for a write takes an explicit durability mode:

```java
enum Durability {
  /** Buffered in the OS page cache. Survives process crash, not power loss. */
  NONE,
  /** fsync'd before returning. Survives power loss. */
  SYNC,
  /** Batched with concurrent writers into a shared fsync (group commit). */
  GROUP
}
```

No default that silently picks the weak option. The caller chooses and the Javadoc
states exactly what surviving what is promised.

**D2 — Mark the durability point.** The exact line where data becomes durable carries a
comment:

```java
// DURABILITY: after force() returns, records up to lastSequence survive power loss.
channel.force(false);
```

`grep -rn "DURABILITY:"` must enumerate every such point in the codebase. If that grep
returns something you did not expect, that is a bug.

**D3 — Order is part of the contract.** The WAL record is durable *before* the memtable
is updated, and the memtable is flushed *before* the covering WAL segment is deleted.
Any code that reorders these is wrong regardless of how much faster it is. State the
ordering invariant in a comment at each site.

**D4 — Never widen a guarantee, never narrow it silently.** Changing a default from
`SYNC` to `NONE` is a breaking change and needs an ADR and a `Reversible: no` trailer.

**D5 — Every durability claim has a crash test.** If the Javadoc says a write survives
power loss, there is a test that kills the process at that point and asserts it did.
An untested durability claim is a marketing claim.

---

## 6. Backpressure

An LSM engine that accepts writes faster than compaction can absorb them does not fail
cleanly — it accumulates L0 files until reads collapse and space runs out.

- Write stalls are explicit and observable: a stall increments a counter, logs at
  `WARN` with the trigger, and is exposed as a metric. Never stall silently.
- Thresholds are named configuration, not literals:
  `l0SlowdownWritesTrigger`, `l0StopWritesTrigger`, `pendingCompactionBytesLimit`.
- Slowdown before stop: rate-limit first, reject last.
- Never drop a write to relieve pressure. Block or reject with a typed exception; a
  storage engine that discards accepted data has failed at its only job.
