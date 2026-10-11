# 0012. Make a manifest the authority for live files, and recover through it

- **Status:** Accepted
- **Date:** 2026-10-11
- **Milestone:** M5
- **Reversible:** no — it creates a new on-disk file (the manifest, born at version 2 on log
  framing v2) and the `CURRENT` pointer, and adds public API (`ShaleOptions`, the `Env` SPI,
  `close()` and failed-state semantics). An M4 directory still opens (it is migrated on the first
  open); a directory opened by M5 cannot be opened by an M4 build without losing its file set.

## Context

Through M4 the set of live files is whatever `Shale.open` finds by listing the directory: every
`NNNNNN.sst` is a table and every `NNNNNN.wal` a segment to replay. That works only while no file
is ever deleted. M6's compaction must delete its inputs, and once the directory is the authority
a deleted-too-early file, or one left behind by a crash, silently changes the database. A
database needs a durable record of which files belong to it, and a single point at which that
record changes.

The October 2026 engineering review also left six lifecycle findings for this milestone:

| Id | What goes wrong today |
|---|---|
| SH-01 | a failed flush drops its frozen memtable from the read view while writes continue; after reopen `get` and `scan` disagree |
| SH-02 | an `open` that fails part-way leaks every table it had opened |
| SH-04 | no directory entry is ever synced, so `SYNC` is not power-loss durable across a segment switch or a flush |
| SH-05 | nothing proves the durability claim under power loss: the crash tests only kill the process |
| SH-09 | file names past 999,999 are mis-parsed and mis-ordered; the newer files vanish on the second reopen |
| SH-10 | a cursor whose first seek throws keeps its table references forever |

The constraint that drives every choice below is **correctness under crash**, including power
loss: at every point in every write path, the durable state must be either the old database or
the new one, and an `open` that refuses its inputs must change none of them (ADR-0024 §4).

The full design, step by step, is [the M5 plan §2](../roadmap/m5-manifest-and-recovery.md#2-the-design-decided--adr-0012-records-it-with-the-alternatives).
This record states what was decided, what was rejected, and why.

## Options considered

### Where the set of live files comes from

#### Option A — keep directory discovery as the authority
Today's code; no new format. Cleaning up after a crash would rely on naming conventions (a
`.tmp` suffix, a "pending delete" marker). It cannot express "this table replaced those three":
a crash between writing a compaction's output and deleting its inputs leaves both, and both are
read. There is no point at which deleting a file is provably safe. **Rejected: no safe deletion.**

#### Option B — one metadata file, rewritten whole on every change
Write the full file set to `MANIFEST.tmp`, sync, rename over the old one, sync the directory. Easy
to reason about, and correct. But every flush rewrites the whole state — O(files) bytes and a
rename plus a directory sync per flush — and the cost grows with the database. **Rejected:
O(state) per flush.**

#### Option C — a log of version edits, named by a `CURRENT` pointer (LevelDB, RocksDB)
The manifest is an append-only log: one full-state edit at its start, then one *delta* edit per
change (files added, files deleted, counters). Applying an edit is appending one record and
syncing it. `CURRENT` names the live manifest and changes only when a new manifest is started (at
open, and when the manifest grows past a threshold). LevelDB's `VersionSet` and RocksDB's
`VersionSet` both work this way; the format here follows LevelDB's `version_edit.cc` tags.

### How often `CURRENT` changes

#### Option C1 — rewrite `CURRENT` on every edit
Each edit writes a new manifest file and switches `CURRENT`. The manifest then never needs a
tail rule, but every flush pays an extra file, a rename and a directory sync. **Rejected: an extra
rename and directory sync per flush, for nothing the log does not already give.**

#### Option C2 — rewrite `CURRENT` only at open and at rollover (LevelDB)
The edit's own sync is the commit point; a torn record at the end of the manifest was never
synced, so it never took effect (ADR-0024 §2's tail rule decides it).

### How a crash is simulated in tests

#### Option D1 — an in-memory simulated filesystem
Every test runs against a model filesystem that loses unsynced state on "power loss". Fast, but
it is a second filesystem whose semantics must themselves be right, and a bug in it is a bug the
suite cannot see. **Rejected: a second filesystem to get right.**

#### Option D2 — inject faults into a real filesystem (RocksDB's `FaultInjectionTestEnv`)
Wrap the real `Env`, record each file's length at its last `sync` and each directory's
operations since its last directory sync. Power loss truncates files and undoes unsynced
directory operations. This is exact for the engine because every file it writes is append-only
or written once and renamed.

### What a failed flush does to the engine

#### Option E1 — retry the flush in place
Keep every frozen memtable that has not reached a table visible and retry on the next switch.
Provable, but it is a state machine of pending immutables, partial tables and partial edits that
reopen already handles. **Rejected: reopen gives the same result with no new state.**

#### Option E2 — a failed state (LevelDB's `bg_error_`)
The first flush failure stops the engine accepting writes. Reads keep serving the last published
view. `close()` and reopen recovers from durable state.

### Whether the I/O seam is public

#### Option F1 — an internal seam, used only by `shale-core`'s tests
Fewer public types. But ShaleDB's crash tests (D2 onward) must inject faults through
`Database.open`, and `shale-db` may use only `shale-core`'s public API (CLAUDE.md §2). **Rejected:
it would force `shale-db` into `internal`, or leave the database layer without crash tests.**

#### Option F2 — a public `Env` SPI (LevelDB, RocksDB)
`dev.shale.env.Env` is public; the default is `PosixEnv`. The fault-injection `Env` ships in
`shale-core`'s test fixtures, not its main jar.

## Decision

We take **C with C2, D2, E2 and F2**. Each item below is specified in full in the plan section
named.

1. **Files in a directory** (plan §2.1): `LOCK`, `CURRENT`, `MANIFEST-N`, `N.wal`, `N.sst`, and
   `*.tmp` (deleted at open, never read). One monotonic counter numbers every file. `%06d` is a
   *minimum* width; names match `\d{6,}` and are ordered numerically (SH-09).
2. **Manifest format v2** (plan §2.2): log framing v2 (ADR-0024) with magic `ShaleMAN`
   (`0x5368616C654D414E`) and `FORMAT_VERSION` **2**. There is no manifest v1: the file header's
   version *is* the framing version, and version 1 would select the unchecksummed headers that
   M4.5 removed. One logical record is one `VersionEdit` of `(tag, field)` pairs, tags 1–7 as in
   LevelDB, except that tag 7 (new file) also carries the file's smallest and largest sequence, as
   RocksDB's `FileMetaData` does, so M6 can order files by sequence once numbers stop implying
   age. Tag 5 (compaction pointer) is defined now and first written by M6. An unknown tag, an
   overrunning field or a level outside 0–6 is corruption. A torn final record was never synced
   and is dropped; any other damage is corruption.
3. **Open** (plan §2.3), in order: take `LOCK`; delete `*.tmp`; load the state from `CURRENT`'s
   manifest, or migrate an M4 directory by discovery (every table at level 0, log number 0), or
   start empty; open every table (a missing one is corruption); replay WAL segments from the log
   number (only the newest may be torn); flush a non-empty replay to a level-0 table; write a new
   manifest holding one full-state edit; **switch `CURRENT` (the commit point of open)**; create
   the new segment; delete obsolete files; publish. Everything acquired goes into one list that
   is closed in reverse if any step before publishing throws (SH-02). Nothing is deleted before
   the commit point, so a refused open changes nothing; files written by a failed attempt are
   orphans that the next successful open deletes.
4. **Flush** (plan §2.4): switch the memtable and segment (the new segment's header and directory
   entry are synced: SH-04); write the table to `.tmp`, sync, rename, sync the directory; append
   the edit to the manifest and sync it — **the durability point of the flush**; install the new
   Version; publish; delete segments below the new log number. Past
   `ShaleOptions.manifestRolloverBytes` (default 4 MiB) a new manifest is started as open does.
5. **Ownership** (plan §2.5): an immutable `Version` retains the tables it lists; `VersionSet`
   owns the current Version and the manifest writer. `logAndApply` syncs the edit, swaps the
   Version and marks each deleted table obsolete. **`SSTableReader.release()` at zero deletes an
   obsolete file**, so a file disappears exactly when it is out of the durable Version and no
   reader holds it. `get` acquires and releases a Version; `scan` hands its reference to the
   cursor, which adopts it and releases it on `close()`, or `scan` releases it if the cursor
   cannot be built (SH-10).
6. **Failure and close** (plan §2.6): an `IOException` in a flush's switch, table, edit or
   rollover puts the engine in the **failed state** (SH-01). Writes then throw
   `EngineStateException("engine failed; reopen to recover", cause)`; reads keep the last view,
   frozen memtable included. The write that triggered the failing flush was already logged and
   applied, so it **returns normally**; the next write reports the failure. `close()` is
   idempotent; after it every operation throws `EngineStateException("engine is closed")`, while
   cursors opened before it keep working on their own Version reference.
7. **The seam and the harness** (plan §2.7): a public `dev.shale.env.Env` with `WritableFile` and
   `ReadableFile`, and `PosixEnv` as the default (`FileChannel.force(true)` for files, and for
   directories opened read-only). `FaultInjectionEnv` in `shale-core`'s test fixtures gives an
   indexed trace of mutating operations with `crashAt(n)` (a `SimulatedCrash` `Error`, which no
   `catch (IOException)` can swallow), `dropUnsyncedData(seed)` for power loss (with a `TORN`
   mode), `openHandles()`, and `failOn(n, IOException)` (SH-05).

### Amendment to ADR-0008

ADR-0008's power-loss claim, already qualified by ADR-0024 §7, is amended: **a `SYNC` write is
power-loss durable once its record is forced *and* every directory entry it depends on is
synced** — its segment's entry (synced when the segment is created), and after a flush, its
table's entry (synced after the rename, before the manifest edit that makes the table live). From
M5 this is true of every path, and the crash matrix tests it under simulated power loss. Physical
power loss stays untested, argued from fsync ordering only (ADR-0024 §7).

### Public API and behaviour changes

| Change | Kind |
|---|---|
| `ShaleOptions` (record: `writeBufferSizeBytes`, `manifestRolloverBytes`, `env`; `defaults()`, `withX`) and `Shale.open(Path, ShaleOptions, Clock, Metrics)`; existing overloads delegate. The `Env` is an option, as in LevelDB's `Options::env` | new API |
| `dev.shale.env`: `Env`, `WritableFile`, `ReadableFile`, `PosixEnv` | new SPI |
| `dev.shale.manifest`: `VersionEdit`, `VersionEditCodec`, `ManifestWriter`, `ManifestReader`, `CurrentFile`, `FileMetadata`, `Version`, `VersionSet` | new API |
| `close()` is idempotent; `put`, `delete`, `get`, `scan` after it throw `EngineStateException` | behaviour |
| a second `open` of a held directory throws `EngineStateException` (`LOCK`) | behaviour |
| the failed state: writes after a flush failure throw `EngineStateException` with the cause | behaviour |
| `ReconcilingCursor` adopts one reference instead of retaining a list | internal API |
| `SSTableReader.markObsolete()`; `SSTableWriter` reports key and sequence ranges and size | API |
| `CorruptionException` gains a constructor with a `Path`; the message names the file | API |

## Rationale

- **C over A** because safe deletion is the whole point: M6 cannot exist on discovery. **C over
  B** because a flush must cost O(edit), not O(database), and the log of edits gives that with
  the framing M4.5 already hardened. Reusing that framing means the manifest inherits ADR-0024's
  verified tail rule: a damaged manifest length is corruption, never a silently dropped edit.
- **C2 over C1** because the edit's own sync is already an atomic commit point; switching
  `CURRENT` per edit adds two durable operations per flush and no guarantee.
- **D2 over D1** because the property under test is the engine's ordering of real syscalls. The
  engine writes only append-only files and renames, so "truncate to the last sync, undo unsynced
  directory operations" is an exact model of what a POSIX filesystem may lose, run on the real
  filesystem the engine uses in production. A model filesystem would test our model.
- **E2 over E1** because recovery already has to rebuild from durable state after any crash.
  Making a failed flush reuse that path removes a state machine instead of adding one, and
  LevelDB and RocksDB both made the same choice (a sticky background error). The cost — a reopen
  after any flush I/O error — is acceptable for a single-node study engine and is stated.
- **Letting the triggering write return normally** because it *was* durable to its mode before
  the flush began; reporting the flush failure against it would tell the caller a durable write
  failed, and a caller that retries it would write it twice.
- **F2 over F1** because the dependency rule forbids the alternative: the database layer's crash
  tests are a deliverable, and they need the seam through public API.
- **Per-file sequence ranges in tag 7** cost two varints per file now and save a format change in
  M6, where level-0 ordering by file number stops being correct.

The learning objective is served directly: this is LevelDB's `VersionSet`/`VersionEdit`/
`CURRENT` design, reproduced and then tested under simulated power loss, which LevelDB's own
suite does through the same kind of fault-injecting `Env`.

## Consequences

**Positive:**
- Deleting a file becomes provably safe, which unblocks M6.
- SH-01, SH-02, SH-04, SH-05, SH-09 and SH-10 each close with a regression test.
- `SYNC` is power-loss durable on every path, and the claim is tested under simulated power loss.
- The `Env` seam lets every later layer (ShaleDB's transactions and server) write crash tests.

**Negative:**
- A second durable log to maintain, with its own format, golden and bit-flip tests.
- Open does more work: a full-state manifest rewrite and a `CURRENT` switch on every open.
- A flush I/O error now makes the engine refuse writes until it is reopened.
- An M5 directory cannot be read correctly by an M4 build (it would ignore the manifest and
  read any orphan table).
- `Env` is public API, so changing it later is a breaking change for `shale-db`.

**Neutral:**
- Flush stays synchronous under the write lock; M5.5 moves it to a background thread with the
  same failure contract.
- Discovery code survives only as the one-way migration of an M4 directory.

**If we need to reverse this:** there is no going back to discovery without giving up safe
deletion. Changing the manifest's record layout is a `FORMAT_VERSION` bump with a reader for
version 2 kept, as on-disk-formats.md §3 requires. The `Env` SPI can grow methods with default
implementations; removing one is a breaking change requiring a new ADR.

## References

- LevelDB `db/version_set.cc`, `db/version_edit.cc` (tags), `db/filename.cc` (`CURRENT`,
  `SetCurrentFile`), `db/db_impl.cc` (`Recover`, `bg_error_`), `include/leveldb/env.h`.
- RocksDB `db/version_edit.h` (`FileMetaData` sequence ranges), `utilities/fault_injection_env.h`
  (`FaultInjectionTestEnv`), and the wiki page "MANIFEST".
- Pillai et al., "All File Systems Are Not Created Equal" (OSDI 2014): which file and directory
  operations survive a crash, and in what order.
- Alex Petrov, *Database Internals* (O'Reilly, 2019), ch. 7 "Log-Structured Storage".
- The October 2026 engineering review: SH-01, SH-02, SH-04, SH-05, SH-09, SH-10
  ([bug-log.md](../bug-log.md) keeps their evidence).
- [M5 plan](../roadmap/m5-manifest-and-recovery.md) §2; [[0007-wal-block-log-format]],
  [[0008-durability-and-clock]] (amended here), [[0011-internal-iterator-seam]],
  [[0024-recovery-integrity-boundaries]].
