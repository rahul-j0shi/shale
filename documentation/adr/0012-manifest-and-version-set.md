# 0012. Record the live file set in a manifest log of version edits

- **Status:** Proposed
- **Date:** 2026-09-07
- **Milestone:** M5
- **Reversible:** no — the manifest is a new on-disk format, and `CURRENT` fixes how a database is discovered. A database written by this version cannot be opened by an M4 binary.

## Context

Through M4 the engine has no record of what it consists of. `Shale.open` scans the directory
for `NNNNNN.sst`, opens every file it finds, and calls that the database. Four consequences
follow, and M5 exists to remove all four:

1. **A crash between install and cleanup duplicates data.** `switchAndFlush` renames the new
   table into place and then deletes the WAL segment that also held its records
   (`Shale.java:250-254`). A process that dies between those two lines leaves both. The next
   open finds the table *and* replays the segment, producing a second table with byte-identical
   `(user key, sequence)` pairs. Reads stay correct — reconciliation dedups by user key — but
   ADR-0011 and the M4 architecture doc both assert that exact internal-key ties are impossible,
   and today they are reachable.
2. **Every reopen writes a table.** Recovery replays the log into a memtable and flushes it,
   even for one record, so a service restarted a thousand times accumulates a thousand tiny
   tables (`Shale.java:172-180`). Recovery is not supposed to be a mutation.
3. **Nothing can safely delete a file.** `SSTableReader` is reference-counted and the cursor
   pins what it reads (N6), but `get` takes a bare volatile read of the view and probes tables
   without retaining them (`Shale.java:288-305`). That is safe only because nothing deletes
   tables yet. M6's compaction deletes tables constantly.
4. **A database can be opened with the wrong comparator.** The ordering of every file depends
   on the user comparator, and its name is stored nowhere. Opening with a different one yields
   silent, arbitrary misbehaviour — the failure mode N4 exists to prevent.

The driving constraint is crash consistency: the set of live files must change **atomically**
with respect to a crash. There is no point deleting a superseded file if a crash between "write
the new one" and "record that it exists" can lose both.

## Options considered

### Option A — a log of version edits, with `CURRENT` naming the active log

The manifest is an append-only log of *edits* — "file 7 added, files 3 and 4 deleted, next file
number is 9, last sequence is 4021". A version is the fold of every edit from the start.
Installing a new version means appending one edit and fsyncing it; that single append is the
atomic commit point. A separate one-line `CURRENT` file names the active manifest, replaced by
temp-file-plus-rename, so discovery itself is atomic. On open the engine reads `CURRENT`,
replays the manifest to rebuild the file set, then replays only WAL segments newer than the log
number the manifest records.

This is LevelDB and RocksDB. Its cost is that a long-lived database accumulates edits, so the
manifest is periodically rewritten starting from a full snapshot of the current version.

### Option B — rewrite the complete file list on every install

Each install writes the entire live set to a new file and renames it over the old one. No
replay, no fold, no compaction of the manifest itself; recovery is a single read.

Simple, and genuinely adequate at M5's scale. It costs O(live files) bytes per install, which
is nothing for ten tables and real for ten thousand once compaction is installing versions
continuously. More importantly it discards the *edit* as a concept, and the edit is what makes
an install a single atomic append rather than a whole-file rewrite.

### Option C — no manifest; make the directory scan crash-safe

Keep discovery by directory listing and solve the duplicate-table problem some other way — for
instance by naming tables deterministically from the WAL segment they came from, so a replay
after a crash overwrites rather than duplicates.

This keeps the current shape and needs no new format. It cannot express "these two files are
the same version" or "this file is superseded but still being read", so it cannot support
delete-on-zero, and it leaves the comparator unchecked. It postpones M5 rather than doing it.

## Decision

**Option A.** A manifest log of version edits, reusing the WAL's block-log framing (ADR-0007),
with a `CURRENT` file naming the active manifest.

Specifically:

- **Framing is reused, not reinvented.** The manifest is a block log — 32 KiB blocks, 7-byte
  fragment header, CRC32C per fragment, `FULL`/`FIRST`/`MIDDLE`/`LAST` — exactly as ADR-0007
  defines for the WAL, with its own magic and its own `FORMAT_VERSION`. Only the *payload*
  differs: a WAL payload is one mutation, a manifest payload is one `VersionEdit`.
- **A `VersionEdit` payload is a sequence of tagged fields**, LevelDB's encoding: a varint tag,
  then that tag's value, repeated until the payload is exhausted. Absent tags mean "unchanged".

  | Tag | Field | Encoding |
  |---|---|---|
  | 1 | comparator name | varint length + UTF-8 bytes (written in the first edit of a manifest) |
  | 2 | log number | varint — WAL segments numbered below this are covered by a flush and obsolete |
  | 3 | next file number | varint |
  | 4 | last sequence | varint |
  | 5 | deleted file | varint level, varint file number |
  | 6 | added file | varint level, varint file number, varint size in bytes, varint+bytes smallest internal key, varint+bytes largest internal key |

  **An unknown tag is `CorruptionException`, never a skipped field.** A manifest is a
  description of which files exist; silently ignoring a field could silently drop a file, and
  N4 forbids continuing past something we do not understand. This makes the format explicitly
  *not* forward compatible, which is the correct trade here (on-disk-formats.md §3).

- **An added file records its level and key range from the start**, even though M5 has neither
  levels nor a use for the range: level is always 0 until M6, and the smallest and largest keys
  are already known to the writer at flush time, so nothing has to be computed to fill them.
  M6's file picking needs all five fields, and writing them now costs one varint and two keys
  per file while the format is new and no database exists to migrate. The alternative — a
  minimal v1 followed by a v2 bump at M6 — would spend the full §3 procedure (version bump,
  second golden file, compatibility stance) on a change we can see coming from here.
- **`CURRENT` contains one line**: the manifest's file name. It is written to `CURRENT.tmp`,
  fsynced, and atomically renamed. A database with no `CURRENT` is a new database; a `CURRENT`
  naming a file that does not exist is corruption, not an empty database.
- **A `Version` is immutable and reference-counted.** Installing a new one appends its edit,
  fsyncs, then publishes. A file is deleted when the last version referencing it is dropped and
  no cursor still pins it — delete-on-zero.
- **Reads pin the version they use.** `get` retains, probes, releases, exactly as `scan`
  already does through `ReconcilingCursor`. Without this, M6 deletes a table under a running
  read.
- **The comparator name is checked on open** and a mismatch throws with both names.
- **The recovery-flush goes away.** Replayed records go into the active memtable and stay
  there; the segments that fed them are dropped only once a real flush covers them.
- **Every open rewrites the manifest**, beginning the new one with a full snapshot of the
  current version — one added-file record per live table — and then pointing `CURRENT` at it.
  Growth is therefore bounded by the edits of a single run rather than by the lifetime of the
  database, and replay on open stays proportional to the live file set. It also exercises the
  snapshot-at-head path on every start, which is the same path M6 needs for rewriting a manifest
  that has grown large mid-run; a code path taken on every open is one that cannot rot.

## Rationale

The edit log wins on the one force that matters here: **an install is a single append plus one
fsync**, which is the smallest atomic unit the filesystem gives us. Option B's whole-file
rewrite is atomic too, via rename, but it makes every install proportional to the size of the
database, and it is the wrong shape for M6, where compaction installs a version on every merge
and the natural description of that event is precisely "these went away, that one arrived".

Reusing the WAL framing is worth more than the code it saves. The block log is already
specified, already golden-tested, and already proven against truncation at every offset by
`ShaleCrashTest`; a manifest built on it inherits that evidence, and the torn-tail policy it
needs — a partial edit at the tail is a crash during install and must be discarded, while a bad
CRC anywhere else is corruption (N4) — is the policy `WalReader` already implements. Writing a
second framing would mean a second set of the same bugs.

Option C is rejected because it treats the symptom. Deterministic table names would stop the
duplicate, but the manifest is not really about duplicates: it is about being able to say what
the database *is*, atomically, which is what makes deletion, compaction and snapshots possible.
Every one of those is a later milestone that Option C would block.

There is also a deliberate learning choice: the version set is the piece of an LSM engine that
most people who have "written an LSM tree" have skipped, and it is where the interesting
crash-consistency reasoning lives.

## Consequences

**Positive.** Discovery becomes explicit and atomic. Delete-on-zero becomes expressible, which
unblocks compaction. Recovery stops mutating the database. A wrong comparator is refused rather
than silently mis-ordering. Replay is bounded by the log number instead of replaying every
segment present.

**Negative.** A third on-disk format to version, document and golden-test. Three new failure
modes to test deliberately: a torn edit at the manifest tail, a `CURRENT` naming a missing file,
and a crash *during* the open-time rewrite — which is survivable precisely because `CURRENT`
still names the old manifest until the rename lands, but is only survivable if that ordering is
tested rather than assumed. Rewriting on every open makes opening a database a write, which is
mildly surprising and must be stated in `open`'s Javadoc. Reference counting moves from
"hygiene" to "correctness" — a leaked reference is now a leaked file, and an over-release is a
use-after-free on a channel.

Within a single long run the manifest still grows unbounded; rewrite-*when-large* is deferred to
M6, where compaction makes the rate high enough to matter and there is a benchmark to size the
threshold against.

**Neutral.** The engine gains a `Version`/`VersionSet` vocabulary that mirrors LevelDB's, which
makes the code easier to compare against the reference and harder to read for anyone who has
not seen it.

**If we need to reverse this:** there is no in-place migration. A database written with a
manifest cannot be opened by an M4 binary, and reversing would mean re-deriving the file set by
directory scan and accepting the four problems above. The manifest is additive on disk — the
`.sst` and `.wal` formats do not change — so a reversal is a code revert plus deleting
`CURRENT` and the manifest, not a data conversion. That is the migration path, and it is only
sane before M6 makes deletion real.

## References

- LevelDB `db/version_set.cc`, `db/version_edit.cc`, `doc/impl.md` (MANIFEST + CURRENT).
- RocksDB wiki, "MANIFEST" — edit log, `CURRENT`, and manifest rolling.
- Petrov, *Database Internals*, ch. 5 (recovery, atomic install) and ch. 7 (LSM maintenance).
- ADR-0007 (the block-log framing reused here); ADR-0011 (the cursor's pin, which delete-on-zero
  turns from hygiene into correctness).
