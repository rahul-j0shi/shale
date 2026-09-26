# Verified baseline and the ShaleDB direction — 2026-09-26

**Reviewed revision:** `main` at `9cfa7d6`. Source, documentation and ADRs reviewed; no
implementation changes. This supersedes the September 10 assessment's validation limit. It does
not change that assessment's M5 findings, which stand.

## The baseline is now verified

The September 10 review could not run Java. This one installed the pinned toolchain
(`scripts/bootstrap.sh`, Temurin 25.0.3+9) and ran `./gradlew build crashTest`:

- **161 tests, 0 failures, 0 skipped**, across the unit, property, model, concurrency and crash
  tiers.
- Spotless, Checkstyle, `-Werror` and the N1 runtime-dependency check pass.
- One transient failure, before a clean rerun: Maven Central answered HTTP 429 while resolving
  test dependencies. That is an environment risk for CI, not a code defect; the completion plan's
  risk table records it.

The README's "green on JDK 25" claim is therefore confirmed, not only historical.

## What the code shows (confirming the Sept 10 findings)

- Nothing deletes an SSTable, and there is no compaction, so file count and disk use grow
  without bound. A point lookup for a missing key probes every table (there are no filters yet).
- Every reopen that replays a non-empty WAL writes a new SSTable (`Shale.java:173-176`), so
  tables accumulate even when there are no new writes.
- Flush and fsync run under `writeLock`; `Durability.GROUP` behaves like `SYNC`.
- There is no atomic multi-key write and no snapshot.
- `EngineStateException` is defined but never thrown; `close()` is not idempotent, and operations
  after close are not rejected.
- Files are named with six-digit numbers, so file number 1,000,000 onward would not be
  discovered on reopen. This is already an M5 gate.
- The test discipline (crash test at every byte offset, goldens, bit-flip tests, the `TreeMap`
  model) remains the strongest asset.

## The direction changed

The owner's goal for the finished project is a complete database, with this engine as its
storage layer, powering a CRUD application. That conflicted with the charter's non-goals.
[ADR-0013](../adr/0013-build-shaledb-relational-layer.md) resolves it:

- a relational layer, **ShaleDB**, in new modules above `shale-core` (`shale-db`,
  `shale-server`, `shale-demo`), hand-written and dependency-free;
- built as milestones D1–D6 after the engine is finished and measured (M5–M8);
- the COW B+Tree comparison (now M8b) and Flotilla (M9, M10) become optional capstones after
  v1.0.

The earlier critique's case for the B+Tree — that ADR-0002 and ADR-0006 justify the SPI by that
comparison — is kept, not overruled. M8b is postponed and bounded, not deleted, and gains a
sharper purpose: the same SQL layer running on both backends.

## Where to go from here

Everything left to build, in order, is in the
[completion plan](../roadmap/completion-plan.md). The next action is unchanged from Sept 10:
write ADR-0012 and implement [M5](../roadmap/m5-manifest-and-recovery.md).
