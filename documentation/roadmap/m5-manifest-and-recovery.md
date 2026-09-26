# M5 — Manifest and recovery: implementation plan

**Status:** next to implement. Baseline verified green on JDK 25 at `9cfa7d6` on 2026-09-26
(161 tests). **Depends on:** M4. **Next after M5:** [M5.5](m5-5-concurrent-write-path.md), per the
[completion plan](completion-plan.md).

**Goal:** recover the committed live-file set from durable metadata instead of a directory
listing, and reclaim obsolete files only after readers release them. Without this no file can
ever be deleted safely, so compaction (M6) is impossible.

**Decision gate:** propose ADR-0012 and accept it before implementation. The following items
are a decision checklist, not an accepted byte layout or public API change. New manifest bytes
require a versioned `format.md`, golden fixtures and `Format-Change:`.

## Decisions required in ADR-0012

1. **Version metadata:** define Version/VersionEdit and per-file number, level, size and key
   bounds; comparator identity, sequence/file-number high-water marks and exact WAL replay
   set or boundary. Specify how a durable edit excludes a flushed segment from replay.
   Reserve M6's per-level compaction pointers now, so compaction needs no manifest bump.
2. **Log framing:** decide reuse of WAL block framing without conflating manifest edits with
   mutation payloads. Specify magic/version, edit atomicity, unknown fields, torn-tail versus
   interior corruption, and manifest checkpoint/rollover behavior.
3. **Installation:** specify persistence ordering for table contents and name, manifest edit,
   in-memory publication and WAL reclamation. CURRENT selects a manifest; it is not replaced
   for every edit. Cover initial creation and rollover, file/directory persistence operations
   and supported filesystem assumptions. Atomic rename alone is not a power-loss protocol.
4. **Compatibility:** distinguish a new empty database, legacy M4 data without CURRENT and
   damaged M5 metadata. Choose explicit migration or rejection; never silently fall back to
   directory discovery. Cover missing referenced files, orphans, temporary files and crashes
   during initialization/recovery. Validate metadata before destructive cleanup.
5. **Counters and replay:** recover high-water marks from metadata plus WAL, account for orphan
   filenames and prevent number reuse. Choose WAL reuse/rotation and ownership of replayed
   memtables. Reopens without writes must not accumulate tables or empty WALs. Fix or explicitly
   bound the six-digit filename discovery/parsing limit before numbers exceed it.
6. **Ownership:** acquiring a Version must be safe against replacement and final release;
   a volatile load followed by unconditional retain is insufficient. Pin for both get and scan,
   with exception-safe construction/release. Delete only files obsolete in durable metadata
   AND unowned by all live Versions/readers. Normal close must preserve committed files.
7. **Failures and close:** define state after append/force/rename/install/delete failure,
   including whether further writes are rejected. Cover partial-open cleanup, repeated close,
   operations after close, concurrent reads/close and duplicate opens of one directory.
8. **Comparator validation:** Shale.open currently hardcodes bytewise ordering. A metadata
   fixture can test persisted-name mismatch without adding a public comparator option solely
   for that test. Any API expansion needs an explicit decision.

## Recommended positions for ADR-0012

The ADR author confirms or overrides each; they are here so no question blocks the start.

1. **Metadata:** LevelDB's `VersionEdit` fields, each tagged:
   - comparator name;
   - log number (the oldest WAL segment still needed);
   - next file number and last sequence;
   - per-level compaction pointer;
   - deleted file (level, number);
   - new file (level, number, size, smallest and largest internal key).

   An unknown tag is corruption; a new field means a version bump.
2. **Framing:** reuse the WAL's block framing (ADR-0007) through the existing writer and reader,
   with the magic made a parameter. The manifest has its own magic (`"ShaleMAN"`) and
   `FORMAT_VERSION 1`. One edit is one logical record. A torn tail at the end of the manifest was
   never forced, so it never took effect, and is dropped. Interior corruption throws. Roll over to
   a new `MANIFEST-N`, starting with a full-state edit, when the file passes a size option
   (default 4 MiB).
3. **Installation order:** new table to a temp file → force → rename → force the directory;
   append the edit → force the manifest; publish the new Version; delete obsolete files once
   unreferenced. A new manifest follows the same steps: write and force it, write and force
   `CURRENT.tmp`, rename it to `CURRENT`, force the directory.
   - **Directory force:** `FileChannel.open(dir, READ).force(true)`.
   - **Supported platforms:** Linux and macOS, stated in the ADR; Windows is not a target.
4. **Compatibility:** a directory with no `CURRENT` but `.sst`/`.wal` files is the M4 layout.
   Migrate it once with the existing discovery code: every table at L0, the sequence from a scan,
   then write the first manifest. Test against a checked-in M4 directory fixture. A `CURRENT` with
   an invalid manifest throws `CorruptionException` and never falls back to discovery.
5. **Counters and replay:**
   - Next file number = one past the highest number in the manifest or in any file name present
     (orphans included). Last sequence = the maximum of the manifest's and the replayed WAL's.
   - **Recovery flush:** keep flushing a non-empty replayed memtable at open (LevelDB's default).
     Install it through a manifest edit that advances the log number, then delete the replayed
     segments. Note: a reopen *without* writes already adds nothing. The flush is conditional on
     replayed data (`Shale.java:173`), and M6's compaction absorbs the small tables it creates.
   - **File names:** keep `%06d` as a minimum width; parse `\d{6,}` and order numerically.
6. **Ownership:** Versions are reference-counted. A reader acquires the current Version under a
   short lock that guards `current`, and retains it there. That is obviously correct; a lock-free
   `tryRetain` is a later optimisation that needs a benchmark. `get` and `scan` both pin.
   A file is deleted when it is in no durable Version and its last reference drops.
7. **Failures and close:**
   - **Install failure:** a failed manifest append or force, or a failed rename, puts the engine
     in a failed state. Reads continue from the last good Version; writes throw
     `EngineStateException`.
   - **Close:** `close()` is idempotent; operations after it throw `EngineStateException`.
   - **Double open:** a `LOCK` file taken with `FileChannel.tryLock` prevents it.
   - **Delete failure:** counted, and retried at the next open as orphan cleanup.
8. **Comparator:** persist `shale.BytewiseComparator`; a mismatch refuses to open. No public API
   change.

**The crash-test seam** (implementation step 2): an internal `FileSystemOps` interface — open,
append, force, rename, delete, list, force-directory — with the real NIO implementation and a
test-scope `SimulatedFileSystem`. The simulated one tracks forced versus unforced bytes and
directory entries, records an operation trace, and can crash at operation *N*: dropping unforced
data and optionally tearing the last write. It is the engine's first simulation harness.

**Deferred:** group commit and background flush (M5.5), compaction (M6), batches, snapshots and
filters (M7). Lifecycle tests may install test Versions;
M5 does not need a production compactor.

## Implementation order

1. ADR, format specification, golden fixtures and ADR index; record rejected alternatives.
2. Minimal internal file-operations seam across WAL/table/manifest paths, supporting controlled
   write/force/rename/delete failures, torn writes, ENOSPC and volatile/persisted state.
   Record operation traces for reproducibility; do not expand the public backend API.
3. Manifest codec/replay, validation, reader/writer golden checks and rollover tests.
4. Immutable Versions, safe publication/acquisition and obsolete-file reclamation tests.
5. Integrate open/flush, retire directory discovery as the authority (keeping it only for the
   one-time M4 migration), and bind WAL reclamation to durable metadata.
6. Enumerate installation/recovery fault points; extend the reference model and controlled
   concurrency tests. Include recovery interrupted by another crash.
7. Run `./gradlew build crashTest` on JDK 25. Update package docs, as-built architecture,
   README status and the changelog; tag only after gates pass.

## Acceptance gates

- Reopen one record 1,000 times without writes: no new SSTables, bounded WAL count, same value.
  The one-time M4 migration is tested separately, from the checked-in fixture.
- Every flush/install/rollover crash point preserves acknowledged SYNC/GROUP writes. Surviving
  unacknowledged writes may appear under the documented policy; NONE has a weaker guarantee.
  Artificial truncation checks complete surviving records, not bytes deliberately removed.
- Cover table rename before manifest commit and manifest commit before WAL deletion. Recovery
  does not introduce duplicate internal keys across live tables; specify legacy duplicates.
- Missing referenced files, comparator mismatch, invalid edits and interior corruption fail
  explicitly. Permitted torn tails recover a valid prefix; partial edits never apply.
- Point reads and cursors survive Version replacement. Obsolete files remain while pinned and
  disappear on final release; committed files survive close/reopen. Exercise acquisition races
  and failure cleanup, not just refcount arithmetic.
- Force/install failures cannot acknowledge uncertain durability. Deletion failure preserves
  a recoverable copy and has a defined retry/reopen policy.
- Sequences/file numbers stay monotonic through replay, orphan cleanup and the six-digit
  boundary. Existing model, format, crash and concurrency tests stay green.

## Subsequent milestones

Ordered in the [completion plan](completion-plan.md), each with its own plan.
