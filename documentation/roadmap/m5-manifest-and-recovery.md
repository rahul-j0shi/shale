# M5 — Manifest and recovery: implementation plan

**Status:** planning complete; implementation not started. Reviewed against `812f88c` on
2026-09-10; baseline verified green on JDK 25 at `9cfa7d6` on 2026-09-26 (161 tests). **Depends
on:** M4 and prerequisite tests already landed in `9780bfd` and `812f88c`. **Next after M5:**
[M5.5](m5-5-concurrent-write-path.md), per the [completion plan](completion-plan.md).

**Goal:** recover the committed live-file set from metadata, retain replayed data without an
unconditional recovery flush, and reclaim obsolete files only after readers release them.

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

**Deferred:** group commit/background flush (M5.5), compaction (M6), filters/cache/snapshots/
batches (M7), new modules and distributed work. Lifecycle tests may install test Versions;
M5 does not need a production compactor.

## Implementation order

1. ADR, format specification, golden fixtures and ADR index; record rejected alternatives.
2. Minimal internal file-operations seam across WAL/table/manifest paths, supporting controlled
   write/force/rename/delete failures, torn writes, ENOSPC and volatile/persisted state.
   Record operation traces for reproducibility; do not expand the public backend API.
3. Manifest codec/replay, validation, reader/writer golden checks and rollover tests.
4. Immutable Versions, safe publication/acquisition and obsolete-file reclamation tests.
5. Integrate open/flush, retire directory discovery as authority and unconditional recovery
   flush, and bind WAL reclamation to durable metadata.
6. Enumerate installation/recovery fault points; extend the reference model and controlled
   concurrency tests. Include recovery interrupted by another crash.
7. Run `./gradlew build crashTest` on JDK 25. Update package docs, as-built architecture,
   README status and release note; tag only after gates pass.

## Acceptance gates

- Reopen one record 1,000 times without writes: no new SSTables, bounded WAL count, same value.
  A first legacy migration, if supported, is tested separately.
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

Each has its own plan, ordered in the [completion plan](completion-plan.md):
[M5.5](m5-5-concurrent-write-path.md) (including the pre-M5.5 benchmark baseline),
[M6](m6-compaction.md), [M7](m7-filters-cache-mvcc.md), [M8](m8-benchmark-suite.md), then the
ShaleDB track D1–D6. The notes that used to live here moved into those plans.
