# Errors, logging, and metrics

## 1. The exception hierarchy

Three categories, distinguished because callers must respond to them differently.
Collapsing them into one `IOException` or one `RuntimeException` destroys that
distinction, and it is the distinction that matters.

```
ShaleException (unchecked, abstract)
├── CorruptionException      the data on disk is wrong
├── StorageException         the environment failed (I/O, disk full, permissions)
├── EngineStateException     the engine cannot serve this request right now
└── IllegalArgumentException / IllegalStateException  (JDK, for caller bugs)
```

| Type | Means | Caller should |
|---|---|---|
| `CorruptionException` | Checksum mismatch, bad magic, impossible structure | Stop. Escalate to a human. Never retry, never repair automatically. |
| `StorageException` | Disk full, I/O error, file missing, permission denied | Possibly retry; possibly fail the node. Environmental. |
| `EngineStateException` | Write stalled, engine closed, engine failed, read-only | Back off and retry, or fail fast. Transient or terminal, stated by subtype. |
| `IllegalArgumentException` | Null key, negative size, invalid config | Fix the calling code. Never caught. |

**Unchecked by default.** Checked exceptions are used only where a caller has a
genuine, documented recovery action — in practice almost nowhere in the engine. The
`throws` clauses that checked exceptions produce across a merge iterator or a
compaction pipeline add noise without adding safety.

### Rules

- **`CorruptionException` always carries location and evidence**: file, offset, the
  expected value, the actual value. "Corruption detected" with no offset is nearly
  useless when you have a 200 MiB file and one bad bit.

  ```java
  throw new CorruptionException(
      "block checksum mismatch", file, offsetBytes, expectedCrc, actualCrc);
  ```

- **Never catch and continue on corruption.** No `catch (CorruptionException e) { skip }`.
  Recovery policy is decided by the caller through an explicit `RecoveryPolicy`, never
  by a `catch` block deep in a reader. (See `CLAUDE.md` N4.)
- **Never catch `Exception` or `Throwable`** except at a background-thread boundary,
  where you mark the engine failed and rethrow-or-log. Never swallow `InterruptedException`
  — restore the interrupt flag and propagate.
- **Never wrap without adding information.** `catch (IOException e) { throw new
  StorageException(e); }` is pure noise. Add the file, the operation, and what was being
  attempted, or do not catch it.
- **Exception messages state facts, not blame.** Include the values involved. No
  trailing periods, no exclamation marks, no apologies.
- **One failure state.** Once the engine encounters corruption or a background-thread
  death, it enters a terminal failed state; all subsequent operations throw
  `EngineStateException` referencing the original cause. A half-working engine that
  serves reads while compaction is dead is a data-loss incident waiting to happen.

---

## 2. Logging

SLF4J as the facade. `shale-core` declares no binding — the application chooses.

### Levels

| Level | Use | Frequency |
|---|---|---|
| `ERROR` | Corruption, background thread death, engine failed | Should page a human |
| `WARN` | Write stall, recovery discarded a torn tail, retry, degraded operation | Rare, always actionable |
| `INFO` | Lifecycle: open, close, flush completed, compaction completed, recovery summary, Raft leadership change | Bounded per operation, never per key |
| `DEBUG` | Compaction picking decisions, version installs, per-file detail | Off in production |
| `TRACE` | Per-record, per-block detail | Never enabled outside a debugging session |

**Never log per key on any path.** A single `DEBUG` inside the write path will produce
gigabytes and will change the performance profile you are trying to measure.

### Rules

- Parameterised messages only: `log.info("flushed memtable to {} ({} bytes, {} keys)",
  file, bytes, keys)`. Never string concatenation, never `String.format` — both build
  the string even when the level is disabled.
- Loggers are `private static final Logger log = LoggerFactory.getLogger(X.class);`.
  Always named `log`.
- **Every `INFO` and above includes identifying context**: the file number, the region
  id, the sequence number range, the level. A log line you cannot correlate to a
  specific artifact is decoration.
- Log the outcome of an operation, not its beginning, unless the operation is long
  enough that its start is useful on its own (compaction, recovery — log both, and
  include the duration in the completion line).
- Never log key or value bytes at `INFO` or above. Key *prefixes* at `DEBUG` for
  debugging, truncated and hex-encoded.
- Never log an exception and rethrow it. One or the other; duplicated stack traces make
  incident logs unreadable.

---

## 3. Metrics

Metrics are not an afterthought here — the roadmap's entire premise is that you will
*measure* the read/write/space amplification tradeoffs. Code that cannot report them
cannot demonstrate the thing this project exists to demonstrate.

`shale-core` defines its own minimal `Metrics` interface (counters, gauges,
histograms) with a no-op default, so the engine stays dependency-free and the
application binds it to whatever it likes.

**Required from the start, not retrofitted:**

*Amplification — the headline numbers*
- `bytes.written.user` vs `bytes.written.disk` → write amplification
- `bytes.read.user` vs `bytes.read.disk` → read amplification
- `size.logical` vs `size.disk` → space amplification

*Write path*
- `wal.append.count`, `wal.sync.count`, `wal.sync.duration`, `wal.group.size`
- `memtable.size.bytes`, `memtable.switch.count`
- `flush.count`, `flush.duration`, `flush.bytes`

*Compaction*
- `compaction.count` by level, `compaction.duration`, `compaction.bytes.read/written`
- `compaction.pending.bytes`, `stall.count`, `stall.duration`

*Read path*
- `get.count`, `get.duration`, `get.sstables.probed`
- `bloom.probe.count`, `bloom.negative.count`, `bloom.false.positive.count`
  — the last one requires confirming the miss after the probe; instrument it, because
  measured false-positive rate against the theoretical rate is the fastest way to find
  a broken hash function
- `cache.hit`, `cache.miss` per cache

*Files*
- `sstable.count` by level, `sstable.bytes` by level, `file.open.count`

Naming: lowercase dotted, most-general segment first, unit as the final segment when
not obvious (`.bytes`, `.count`, `.duration`). Never embed a variable in the metric
name — use tags/labels for level, region id, and file kind.

Every metric is readable through a public API and dumped in the engine's `INFO`-level
statistics summary on close, so a benchmark run always ends with the numbers.
