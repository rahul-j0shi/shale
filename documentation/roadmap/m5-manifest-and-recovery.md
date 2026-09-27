# M5 — Manifest and recovery: implementation plan

**Status:** next to implement. Baseline green on JDK 25 at `9cfa7d6` (161 tests).
**Depends on:** M4. **ADR:** 0012 (Step 1 writes it; this plan's §2 is what it records).
**Next:** [M5.5](m5-5-concurrent-write-path.md). **Estimate:** 3–4 focused weeks, in 9 steps.

**Goal.** The set of live files comes from durable metadata (a manifest), not from listing the
directory. That makes it safe to delete a file once nothing references it, which M6's compaction
depends on. On the way, close every known lifecycle gap in the engine, and build the crash
harness that proves the durability claim under simulated power loss, not only process death.

---

## 1. Where the code is today (the starting point)

| Fact | Where |
|---|---|
| Open discovers tables and WAL segments by listing the directory and parsing six-digit names | `Shale.open`, `Shale.filesInOrder`, `Shale.numberOf` (`Shale.java:139-197, 399-419`) |
| Flush: switch memtable + WAL segment, write `NNNNNN.sst.tmp`, force, atomic rename, delete old segment — no directory sync | `Shale.switchAndFlush`, `Shale.flushToSSTable` (`Shale.java:235-275`) |
| Nothing ever deletes an SSTable; `SSTableReader.release()` at zero only closes the channel | `SSTableReader.release` |
| `scan` reads the `volatile` view, then the cursor retains each table — a table can reach zero in between | `Shale.scan`, `ReconcilingCursor` constructor |
| `close()` is not idempotent; `put`/`get`/`scan` after `close()` are not rejected; `EngineStateException` is never thrown | `Shale.close` |
| No `LOCK` file: two engines can open one directory | — |
| A WAL file of 1–15 bytes (a crash while writing a new segment's header) throws `CorruptionException` even under `TRUNCATE_TAIL` | `WalReader.readAll` |
| A new WAL segment's header is not synced, and neither is its directory entry | `WalWriter.open` |
| `CorruptionException` has no file path (N4 asks for file, offset, expected, actual) | `CorruptionException` |
| All file I/O calls `FileChannel`/`Files` directly, so no test can inject a crash between operations | `WalWriter`, `WalReader`, `SSTableWriter`, `SSTableReader`, `Shale` |

## 2. The design (decided — ADR-0012 records it, with the alternatives)

### 2.1 Files in a database directory

| Name | What | Written how |
|---|---|---|
| `LOCK` | held with `FileChannel.tryLock` while open | created once, never written |
| `CURRENT` | one line: `MANIFEST-NNNNNN\n` | written to `CURRENT.tmp`, synced, renamed, directory synced |
| `MANIFEST-NNNNNN` | log of `VersionEdit` records | append + sync per edit |
| `NNNNNN.wal` | WAL segment | append; sync per `Durability` |
| `NNNNNN.sst` | SSTable | written as `NNNNNN.sst.tmp`, synced, renamed, directory synced |
| `*.tmp` | an unfinished write | deleted at open, never read |

- **File numbers:** one monotonic counter for all kinds. The format stays `%06d` as a *minimum*
  width; the parser accepts `\d{6,}` and orders numerically (`long`), so number 1,000,000 works.

### 2.2 Manifest format v1 (`dev.shale.manifest`, `format.md` written in Step 5)

- **Framing:** the WAL's block framing (ADR-0007) — same 32 KiB blocks, fragments and CRC32C — with
  its own file header: magic `0x5368616C654D414E` ("ShaleMAN", `fixed64LE`), `FORMAT_VERSION` 1,
  4 reserved zero bytes.
- **Records:** one logical record = one `VersionEdit`, encoded as a sequence of `(tag, field)`
  pairs. Tags follow LevelDB's `version_edit.cc` numbering:

| Tag (varint32) | Field | Encoding |
|---|---|---|
| 1 | comparator name | varint32 length + UTF-8 bytes |
| 2 | log number (oldest WAL segment still needed) | varint64 |
| 3 | next file number | varint64 |
| 4 | last sequence | varint64 |
| 5 | compaction pointer | varint32 level, varint32 length + internal key |
| 6 | deleted file | varint32 level, varint64 file number |
| 7 | new file | varint32 level, varint64 number, varint64 size bytes, varint32 length + smallest internal key, varint32 length + largest internal key |

- **Validation:** an unknown tag, a field that overruns the record, or a level outside 0–6 is
  `CorruptionException`. Adding a tag later is a version bump.
- **Tails:** a torn record at the very end of the manifest is dropped. It was never synced, so it
  never took effect. Anything else is corruption.
- **Tag 5** is written by M6. It is defined now so M6 needs no format change.

### 2.3 Opening a database (`Shale.open`), in order

1. **Lock.** Create the directory. Take `LOCK`; if it is held, throw
   `EngineStateException("database directory is in use: <dir>")`.
2. **Temp files.** Delete every `*.tmp`.
3. **Load the state.** There are three cases:
   - **`CURRENT` exists:**
     - it must match `MANIFEST-\d{6,}\n`, else `CorruptionException` with the path;
     - replay that manifest's edits in order;
     - a stored comparator name different from `shale.BytewiseComparator` throws
       `IllegalArgumentException` (a configuration error, `errors-and-logging.md` §1).
   - **No `CURRENT`, but `.sst` or `.wal` files present:** this is the M4 layout. Build the state
     with today's discovery code:
     - every table at level 0, ordered by number;
     - last sequence from scanning the tables;
     - log number 0, so every segment replays.
   - **Neither:** a new database — empty, next file number 1.
4. **Open tables.** Open every table in the state. A missing one throws
   `CorruptionException("MANIFEST-N references missing table 000012.sst")`.
5. **Replay the WAL.** Replay segments numbered ≥ the log number, in numeric order, with
   `TRUNCATE_TAIL`, into one memtable.
   - Last sequence = max(state, replayed).
   - Next file number = one past the largest number in the state *or in any file name present*,
     so an orphan's number is never reused.
6. **Recovery flush.** If the replayed memtable is non-empty, write it to a new level-0 table
   exactly as flush step 2 does (`.tmp`, sync, rename, sync the directory), and add the table to
   the in-memory state. No manifest edit yet: the full-state edit of open step 8 records it. This
   is LevelDB's default, and a reopen *without* writes replays nothing and adds nothing.
7. **Numbers.** Allocate the new WAL segment number *W* and the manifest number *M* from the
   counter.
8. **New manifest.** Write `MANIFEST-M` holding one full-state edit (comparator, every file, log
   number *W*, counters); sync it.
9. **Commit point of open.** Write `CURRENT.tmp` (`MANIFEST-M\n`), sync it, rename it to
   `CURRENT`, sync the directory.
10. **New segment.** Create WAL segment *W*: write its header, sync the file, sync the directory.
11. **Obsolete files.** Delete manifests other than *M*, WAL segments below *W*, and tables not in
    the state. A failed delete is counted (`file.delete.failed.count`) and retried at the next
    open; it is never fatal.
12. **Publish.** Publish the read view: an empty active memtable, no immutables, the Version.

- **A crash before open step 9** leaves the old `CURRENT` authoritative. Anything written in open
  steps 6–8 is an orphan, deleted at the next open's step 11.
- **A crash after open step 9** leaves the new state authoritative.

### 2.4 Flushing (still synchronous under `writeLock` in M5; M5.5 moves it)

1. **Switch.** Freeze the active memtable. Close WAL segment *S*. Create segment *S′* (header,
   sync, sync directory). Publish the frozen memtable as immutable.
2. **Write the table.** Write table *T* to `T.sst.tmp`; `SSTableWriter.finish()` syncs it.
   Rename it to `T.sst`, then sync the directory.
3. **Commit it.** `VersionSet.logAndApply(edit)` with edit = new file (level 0, *T*, size,
   smallest, largest key); log number *S′*; next file number; last sequence.
   - Append the edit to the manifest and sync. **This is the durability point of the flush**
     (`// DURABILITY:`).
   - Then install the new Version.
4. **Publish** the view: the active memtable, no immutables, the new Version.
5. **Clean up.** Delete WAL segments below the new log number. A failure here is counted, not
   fatal.
6. **Rollover.** If the manifest now exceeds `manifestRolloverBytes` (default 4 MiB; tests use
   ~1 KiB), write a new manifest with a full-state edit and switch `CURRENT` (open steps 8–9 in §2.3).
   Then delete the old manifest.

**Any `IOException` in flush steps 1–3 or 6 puts the engine in the failed state (§2.6).**

### 2.5 Ownership: `Version`, `VersionSet`, and when a table file is deleted

- **`Version`** (immutable) holds the tables per level. Its constructor retains every
  `SSTableReader` it holds; when its own count reaches zero, it releases them. Versions share
  reader objects: a new Version retains the same readers the old one had, plus new ones.
- **`VersionSet`** owns the current Version (holding one reference) and the manifest writer.
  - `acquire()`: under the `VersionSet` lock, `current.retain(); return current;`.
  - `logAndApply(edit)`: sync the edit, build the new Version, swap `current`, release the old
    one. For every file the edit deletes, call `reader.markObsolete()`.
- **`SSTableReader.release()` at zero** closes the channel, **and deletes the file if it is marked
  obsolete**. A file therefore disappears exactly when it is out of the durable Version *and* no
  reader holds it. This is `concurrency-and-resources.md` §4's rule, now true.
- **Reads:**
  - `get` acquires a Version, reads, and releases it in `finally`.
  - `scan` acquires a Version and hands the reference to the cursor, which **adopts** it (does not
    retain again) and releases it on `close()`. If constructing the cursor throws, `scan` releases
    it.
- **In M5 only a test deletes files through an edit.** M6's compaction is the first production
  caller.

### 2.6 Failure and close

- **Failed state.** A field `failure` (guarded by `writeLock`). Once set, `put` and `delete` throw
  `EngineStateException("engine failed; reopen to recover", failure)`. Reads keep serving the last
  published view. The only way out is `close()` and reopen: recovery then rebuilds from durable
  state.
- **Close.** `close()` is idempotent. It sets `closed`, closes the WAL, releases the VersionSet's
  current reference (files stay: nothing is obsolete), and releases `LOCK`.
  - After `close()`, `put`, `delete`, `get` and `scan` throw `EngineStateException("engine is
    closed")`.
  - Cursors opened before `close()` keep working until they are closed, because they hold their
    own Version reference.

### 2.7 The I/O seam and the crash harness

- **`dev.shale.internal.fs.Env`** — LevelDB's `Env`, cut down to what the engine uses:

  ```java
  public interface Env {
    WritableFile newWritableFile(Path path) throws IOException;  // must not exist
    ReadableFile newReadableFile(Path path) throws IOException;
    byte[] readAllBytes(Path path) throws IOException;
    void rename(Path from, Path to) throws IOException;           // atomic, replaces target
    void delete(Path path) throws IOException;
    void syncDirectory(Path dir) throws IOException;
    List<String> children(Path dir) throws IOException;
    boolean exists(Path path);
    void createDirectories(Path dir) throws IOException;
    AutoCloseable lock(Path lockFile) throws IOException;        // EngineStateException if held
    Env DEFAULT = new PosixEnv();
  }
  interface WritableFile extends AutoCloseable { void append(byte[] b, int off, int len);
                                                 void sync(); long size(); void close(); }
  interface ReadableFile extends AutoCloseable { int read(long pos, byte[] dst, int off, int len);
                                                 long size(); void close(); }
  ```

  `PosixEnv` wraps `FileChannel` (`force(true)` for `sync`; directory sync is
  `FileChannel.open(dir, READ).force(true)`, supported on Linux and macOS). `ReadableFile` is
  LevelDB's `RandomAccessFile`, renamed to avoid clashing with `java.io.RandomAccessFile`.
- **`FaultInjectionEnv`** (test scope, after RocksDB's `FaultInjectionTestEnv`) wraps `PosixEnv`
  over a real temp directory. It gives each test three things:
  - **A trace.** Every mutating operation (create, append, sync, rename, delete, directory sync)
    gets an index. `crashAt(n)` makes operation *n* throw `SimulatedCrash`, an `Error`, so no
    engine `catch (IOException)` can swallow it.
  - **Power loss.** `dropUnsyncedData(seed)` truncates each file to its length at its last `sync`.
    In `TORN` mode it keeps a seeded random prefix of the unsynced tail. It also undoes every
    create, rename and delete not followed by a `syncDirectory` of that directory. This is sound
    because every file the engine writes is append-only.

    How the undo works: the env keeps, per directory, a list of the directory operations since
    that directory's last `syncDirectory`, which clears the list.
    - A **create** is undone by deleting the file.
    - A **delete** moves the file into a hidden trash directory inside the env's root instead of
      removing it; the undo moves it back. `syncDirectory` empties the trash for that directory.
    - A **rename** that replaced a target first moves the old target to the trash. The undo moves
      `to` back to `from` and restores the old target.

    Undo runs newest-first, and file truncation runs after it.
  - **I/O failure.** `failOn(opIndex, IOException)` makes one operation throw a real I/O error, to
    test the failed state.

## 3. New and changed types

| Type | Package | Visibility | Step |
|---|---|---|---|
| `Env`, `WritableFile`, `ReadableFile`, `PosixEnv` | `dev.shale.internal.fs` | public (internal) | 2 |
| `FaultInjectionEnv`, `SimulatedCrash` | `dev.shale.internal.fs` (test) | test | 3 |
| `LogWriter`, `LogReader` (block framing, magic and version as parameters) | `dev.shale.internal.log` | public (internal) | 5 |
| `WalWriter`, `WalReader` | `dev.shale.wal` | unchanged API; delegate to `LogWriter`/`LogReader` | 5 |
| `VersionEdit` (record), `VersionEditCodec`, `ManifestWriter`, `ManifestReader`, `CurrentFile` | `dev.shale.manifest` | public | 5 |
| `FileMetadata` (record: level, number, size, smallest, largest) | `dev.shale.manifest` | public | 6 |
| `Version`, `VersionSet` | `dev.shale.manifest` | public | 6 |
| `SSTableReader` | `dev.shale.sstable` | adds `markObsolete()`; takes an `Env` | 2, 6 |
| `SSTableWriter` | `dev.shale.sstable` | adds `smallestKey()`, `largestKey()`, `fileSizeBytes()` | 7 |
| `ReconcilingCursor` | `dev.shale.iterator` | takes one *adopted* `ReferenceCounted` instead of retaining a list | 7 |
| `CorruptionException` | `dev.shale` | adds a constructor with a `Path`; the message names the file | 4 |
| `Shale` | `dev.shale` | public API unchanged; adds a package-private `open(..., Env)` for tests | 2, 7 |

## 4. Steps

Each step is one branch off `main`, merged green before the next starts. Commit messages follow
`commits.md`, with `Milestone: M5` on every `feat`/`fix`.

### Step 1 — ADR-0012 (`adr/0012-manifest-and-recovery`), ~1 day
1. `docs(manifest)`: ADR-0012 records §2 of this plan (every subsection) with its rejected
   alternatives:
   - directory discovery kept as the authority (rejected: no safe deletion);
   - a manifest rewritten whole on every change (rejected: O(state) per flush);
   - `CURRENT` rewritten per edit (rejected: an extra rename and directory sync per flush);
   - an in-memory simulated filesystem for crash tests (rejected: a second filesystem to get
     right; truncating real append-only files models power loss exactly).

   It also lists the public behaviour changes: `close()` semantics, `LOCK`, the failed state, and
   `ReconcilingCursor` adopting a reference. Set it `Accepted`; update the ADR index.

**Done when:** the ADR is merged. No code changes in this step.

### Step 2 — the `Env` seam (`m05/env-seam`), ~2 days, no behaviour change
1. `refactor(api)`: add `Env`, `WritableFile`, `ReadableFile`, `PosixEnv`, with `package-info`.
2. `test(api)`: `EnvContractTest` — create-new fails if the file exists, append then read back,
   sync, atomic rename replaces the target, delete, children, lock held twice fails.
3. `refactor(wal)`, `refactor(sstable)`: `WalWriter`, `WalReader`, `SSTableWriter` and
   `SSTableReader` take an `Env`. Their existing public `open` overloads delegate with
   `Env.DEFAULT`.
4. `refactor(api)`: `Shale` does all file operations through its `Env`; add a package-private
   `open(Path, Clock, Metrics, long, Env)`.

**Done when:** all 161 existing tests pass unchanged, and `grep -rn "FileChannel.open\|Files\."`
in `shale-core/src/main` finds only `PosixEnv`.

### Step 3 — the crash harness (`m05/fault-injection-env`), ~3 days
1. `test(api)`: `FaultInjectionEnv` + `SimulatedCrash`.
2. `test(api)`: `FaultInjectionEnvTest`:
   - unsynced appends vanish on `dropUnsyncedData`;
   - an unsynced create or rename is undone, a synced one stays;
   - a delete not followed by a directory sync is undone;
   - `crashAt(n)` throws at exactly operation *n*;
   - `TORN` mode keeps a seeded prefix.

**Done when:** `FaultInjectionEnvTest` is green.

### Step 4 — lifecycle fixes that need no manifest (`m05/lifecycle-fixes`), ~3 days
One `fix` commit each, each with the test that fails without it:
1. **WAL segment creation is durable before first use.** `WalWriter.open` syncs the header;
   `Shale` syncs the directory after creating a segment. Test: new `ShalePowerLossTest` (tag
   `crash`) — `SYNC` puts, then a crash and power loss at every operation index, then reopen:
   every acknowledged write is present. It fails before this fix, because neither the new
   segment's header nor its directory entry is synced (§1).
2. **A torn segment header is a torn tail.** Under `TRUNCATE_TAIL`, a 1–15-byte segment is
   empty; under `STRICT` it throws. Test: `WalTest` truncates a fresh segment at every offset
   0–15.
3. **Flush syncs the directory after the rename.** Test: `ShalePowerLossTest` covers a flush.
4. **`close()` is idempotent; later calls throw.** Test: `ShaleLifecycleTest` — double close;
   `put`/`delete`/`get`/`scan` after close throw `EngineStateException`; a cursor opened before
   close still reads.
5. **`LOCK`.** Test: a second `open` on the same directory throws `EngineStateException`; after
   `close()`, open succeeds.
6. **File numbers past six digits.** Names parse as `\d{6,}` and sort numerically, as `long`.
   Test: a directory with `999999.sst` and `1000000.sst` opens with both, in the right order.
7. **`CorruptionException` names the file.** Add a `Path` constructor and use it in `WalReader`
   and `SSTableReader`. Test: `CorruptionExceptionTest` checks the message contains the file name.

**Done when:** all seven tests pass; `build crashTest` green.

Step 4 changes public behaviour (`close()`, `LOCK`), which ADR-0012 (Step 1) already records.

### Step 5 — the manifest format (`m05/manifest-format`), ~3 days
1. `docs(manifest)`: `manifest/format.md` — header, record layout, the tag table, a worked hex
   example of one new-file edit, and the version history.
2. `refactor(wal)`: extract the framing into `LogWriter`/`LogReader` (magic and version are
   parameters); `WalWriter`/`WalReader` delegate. `GoldenWalTest` stays green — the proof the
   bytes did not change.
3. `feat(manifest)`: `VersionEdit`, `VersionEditCodec`, `ManifestWriter`, `ManifestReader`,
   `CurrentFile`.
4. `test(manifest)`:
   - `VersionEditCodecPropertyTest`: round-trip over generated edits;
   - `VersionEditCodecTest`: unknown tag, overrunning field, level out of range;
   - `CurrentFileTest`: valid, missing newline, garbage;
   - `GoldenManifestTest`: fixture `golden/manifest/v1/three-edits.manifest` and its `.json`;
   - a bit-flip test at every offset.

   Commit with `Format-Change: manifest v1 — new file` and `Reversible: no`.

**Done when:** the golden and bit-flip tests pass and `format.md` matches the golden bytes.

### Step 6 — `Version` and `VersionSet` (`m05/version-set`), ~4 days
1. `feat(manifest)`: `FileMetadata`, `Version` (retains its readers, releases them at zero),
   `VersionSet` (`acquire`, `logAndApply`, counters, `markObsolete` on deleted files).
2. `feat(sstable)`: `SSTableReader.markObsolete()`; the release that reaches zero deletes the file
   through the `Env` when it is marked obsolete.
3. `test(manifest)`: `VersionSetTest`, with edits built by hand and a `FaultInjectionEnv`:
   - `acquire` → `logAndApply` deleting a file → the file still exists → `release` → the file is
     gone;
   - two Versions sharing a reader: the file survives until both are released;
   - `logAndApply` with an injected sync failure installs nothing and throws;
   - an edit replayed from the manifest rebuilds the same Version.

**Done when:** `VersionSetTest` is green. Nothing in `Shale` uses the new types yet.

### Step 7 — recovery and flush through the manifest (`m05/recovery`), ~5 days
1. `test(recovery)`: **before changing `open`**, a test-only generator uses the current (M4)
   engine to create `golden/db/m4-layout/`: two SSTables plus one WAL segment with unflushed
   records, with a `.json` of the expected contents. Commit the fixture.
2. `feat(sstable)`: `SSTableWriter.smallestKey()`, `largestKey()`, `fileSizeBytes()`.
3. `feat(iterator)`: `ReconcilingCursor` adopts one reference; update `Shale.scan` and the cursor
   tests.
4. `feat(recovery)`: `Shale.open` implements §2.3, `switchAndFlush` §2.4, reads §2.5, failure and
   close §2.6. The read view holds a `Version` instead of a list of tables.
5. `test(recovery)`: `ShaleManifestRecoveryTest`:
   - reopen 1,000 times without writes: same table set, one WAL segment, one manifest, same value;
   - a missing referenced table → `CorruptionException` naming it;
   - a garbage `CURRENT` → `CorruptionException`;
   - a stored comparator name changed in a fixture → `IllegalArgumentException`;
   - the M4 fixture opens with every record from its `.json`, and afterwards has `CURRENT` and no
     WAL records left to replay;
   - an orphan `.sst` not in the manifest is deleted at open;
   - manifest rollover past `manifestRolloverBytes` keeps every file;
   - an injected I/O failure during flush → writes throw `EngineStateException`, reads still work;
     reopen recovers every acknowledged write.

**Done when:** these tests, plus every existing model, crash and concurrency test, pass.

### Step 8 — the crash matrix (`m05/crash-matrix`), ~3 days
1. `test(recovery)`: `ShaleCrashMatrixTest` (tag `crash`). For each scripted workload, crash at
   *every* operation index; power loss in both modes; reopen with `PosixEnv`; assert.
   - **Workloads:**
     - `SYNC` puts;
     - puts forcing two flushes;
     - a flush that triggers manifest rollover;
     - reopen of a database with unflushed WAL (a crash *during recovery*, then reopen again).
   - **Assertions:**
     - every write acknowledged under `SYNC` is present;
     - nothing appears that was never written;
     - no duplicate internal key across live tables;
     - a reopen that follows `crashAt` then `dropUnsyncedData` never throws
       `CorruptionException`.
2. `docs(docs)`: `concurrency-and-resources.md` §5 — the power-loss row becomes "simulated by
   `FaultInjectionEnv`: unsynced data dropped at every operation index", replacing "asserted by
   construction".

**Done when:** the matrix is green for a fixed seed in CI and for 20 seeds locally.

### Step 9 — documentation and the tag (`m05/docs`), ~2 days
- `package-info` for `internal.fs`, `internal.log` and `manifest` (threading, ownership, N9
  citations: LevelDB `version_set.cc`, `version_edit.cc`, `env.h`; RocksDB `FaultInjectionTestEnv`).
- Glossary rows: `Env`, `WritableFile`, `ReadableFile`, `FaultInjectionEnv`, `VersionSet`,
  `FileMetadata`, `CURRENT`, log number.
- `architecture/m5-manifest-and-recovery.md`: the open sequence, the flush sequence with every
  sync marked, the Version ownership diagram, and the test map. Validate the Mermaid.
- README status, a `CHANGELOG.md` entry, the completion plan's status table.
- **Detail pass for M5.5:** update its plan to name the types M5 actually shipped
  (completion plan §5).
- Tag `m5-manifest`.

## 5. Milestone acceptance gates (each is a test named above)

- Reopen without writes is stable (`ShaleManifestRecoveryTest`).
- No acknowledged `SYNC` write is lost at any operation index under simulated power loss
  (`ShaleCrashMatrixTest`, `ShalePowerLossTest`).
- Files are deleted only when obsolete in durable metadata *and* unreferenced (`VersionSetTest`).
- Corruption, a missing table, a bad `CURRENT` and a comparator mismatch fail loudly and name the
  file (`ShaleManifestRecoveryTest`, `CorruptionExceptionTest`).
- Lifecycle: idempotent close, rejection after close, `LOCK`, failed state
  (`ShaleLifecycleTest`, `ShaleManifestRecoveryTest`).
- Every existing test stays green.

## 6. Not in M5

Background flush and group commit (M5.5); levels other than 0 in production, and compaction (M6);
batches, snapshots, filters (M7).

## References

LevelDB `db/version_set.cc`, `db/version_edit.cc`, `db/db_impl.cc` (`Recover`,
`RemoveObsoleteFiles`), `include/leveldb/env.h`; RocksDB `utilities/fault_injection_env.h`;
Pillai et al., "All File Systems Are Not Created Equal" (OSDI 2014) — why directory syncs matter;
Petrov, *Database Internals* ch. 5 and 7.
