# Naming

Naming is not cosmetic in this codebase. The whole project is an exercise in mapping
literature to code, so an identifier that disagrees with the literature costs real
comprehension. This document is authoritative; where it conflicts with Google Java
Style, this document wins and says why.

---

## 1. Glossary — canonical spellings

Use exactly these forms. Do not invent synonyms. Do not use two names for one concept.

| Canonical | Never write | Meaning |
|---|---|---|
| `SSTable` | `SsTable`, `SSTABLE`, `Table`, `Segment` | Immutable sorted on-disk table |
| `Memtable` | `MemTable`, `memTable`, `MemoryTable` | Mutable in-memory sorted buffer |
| immutable memtable | `sealed`/`rotated`/`frozen table` | A memtable no longer written to, awaiting flush |
| memtable switch | `rotate`, `seal`, `roll` (the memtable) | Freezing the active memtable and starting a new one |
| `Wal` (in identifiers) | `WAL` in identifiers, `Journal`, `Log` alone | Write-ahead log |
| `WalSegment` | `LogFile`, `LogChunk` | One rolled WAL file |
| `Manifest` | `Metadata`, `Catalog` (that is ShaleDB's schema registry) | The log of version edits |
| `VersionEdit` | `Delta`, `Change` | One atomic change to the file set |
| `Version` | `Snapshot` (reserved, see below) | The live set of SSTables at a point in time; reference-counted, so a reader that retains one keeps its tables (ADR-0012) |
| `VersionSet` | `VersionManager`, `FileSet` | Owns the current `Version`, the manifest that makes it durable, and the counters; every change goes through `logAndApply` |
| `FileMetadata` | `TableInfo`, `FileMeta` | What the manifest records per table: level, number, size, key range, sequence range |
| `CURRENT` | `HEAD`, `pointer file` | The one-line file naming the live manifest; switching it is the commit point of open and of a manifest rollover |
| log number | `checkpoint`, `wal position` | The oldest WAL segment the durable state still needs; older segments are already in tables |
| obsolete file | `garbage`, `dead file` | A table removed from the durable `Version` by a manifest edit; deleted by the release that drops its last reference |
| orphan file | `leftover`, `stale file` | A file no manifest names, left by an interrupted operation; deleted at the next open |
| failed state | `read-only mode`, `degraded` | The engine after a flush's I/O failure: writes refused with the cause, reads served, reopen to recover (ADR-0012 §2.6) |
| `Env` | `FileSystem`, `Storage`, `IO` | The engine's I/O seam: every create, append, sync, rename, delete and directory sync (LevelDB's name) |
| `WritableFile` / `ReadableFile` | `OutputFile`, `RandomAccessFile` (clashes with the JDK's) | An `Env`'s append-only file and its positional-read file |
| `FaultInjectionEnv` | `FakeFs`, `CrashEnv` | The test `Env` that crashes at a chosen operation and simulates power loss (RocksDB's name, shortened) |
| `Snapshot` | `Checkpoint` | An MVCC read view, identified by a sequence number |
| checkpoint (`Shale.checkpoint(Path)`) | `backup`, `clone` in identifiers | An online, consistent **copy** of the database directory: flush, pin a `Version`, hard-link the tables (RocksDB's Checkpoint; M8). **Not** SQL's `CHECKPOINT`, which in ShaleDB (D7) flushes and compacts in place and copies nothing; it keeps PostgreSQL's name because clients send it. The SQL command that *does* copy is `BACKUP TO` |
| `SequenceNumber` | `Timestamp`, `Version`, `Txid` | Monotonic per-mutation counter |
| `InternalKey` | `FullKey`, `EncodedKey` | user key + sequence number + value type |
| `UserKey` | `Key` (ambiguous) | The caller's key bytes |
| `Tombstone` | `DeleteMarker`, `Grave` | A deletion record |
| `RangeTombstone` | `RangeDelete` | A deletion record covering a key range |
| `Compaction` | `Merge`, `Cleanup` | Background merge of SSTables |
| `Flush` | `Spill`, `Persist` | Writing an immutable memtable to an SSTable |
| merge iteration | `k-way merge`, `zip`, `interleave` | Reading many ordered sources as one ordered stream |
| `Reconciliation` | `dedup`, `resolve`, `collapse` | Choosing the visible entry per user key: newest wins, tombstones hide |
| `InternalIterator` | `Scanner`, `Reader`, `Stream` | A forward cursor over encoded internal keys, implemented by every read source |
| `Level` | `Tier` (unless tiered compaction) | An LSM level |
| `DataBlock` / `IndexBlock` / `FilterBlock` | `Page`, `Chunk` | SSTable block kinds |
| `RestartPoint` | `Anchor`, `Marker` | Full-key checkpoint in a data block |
| `BlockHandle` | `Pointer`, `Ref` | `{offset, size}` locator inside a file |
| `Footer` | `Trailer`, `Header` | Fixed-size tail of an SSTable |
| `BloomFilter` | `Filter` alone | Probabilistic membership filter |
| torn tail | `partial write`, `truncated record` (for this meaning) | The end of a log left mid-append by a crash: bytes never fully written, so never acknowledged, and safe to drop. Never damaged bytes — those are corruption (ADR-0024) |
| unwritten tail | `zero tail`, `hole` | A log tail of all-zero bytes: space a filesystem allocated but never wrote, exposed after a crash. A kind of torn tail (ADR-0024) |
| unverifiable tail | `ambiguous tail` | In a v1 log segment, a complete fragment header whose payload runs past the end of the file: v1 cannot tell a torn append from a damaged length, so it is refused (ADR-0024 §3) |
| header checksum (`headerCrc`) | `header CRC` in prose is fine; never `hdrCrc` | Log framing v2's CRC32C over a fragment header's first seven bytes, verified before the header is used |
| log framing (`dev.shale.internal.log`) | `log format` (ambiguous with a log's records) | The block-and-fragment layer every Shale log shares: the WAL, and from M5 the manifest |
| `RecoveryPolicy` | `RepairMode`, `Lenient` | The caller's choice of what recovery does with a torn tail: `TRUNCATE_TAIL` or `STRICT` |
| durability mode (`Durability`) | `syncMode`, `fsyncLevel` | What an acknowledged write survives: `NONE`, `SYNC`, `GROUP` (ADR-0008) |
| group commit | `batched fsync`, `coalescing` | One fsync covering many writers' records (ADR-0008; M5.5) |
| `ReadView` | `MemtableSet` (its M2 name), `State` | The immutable set of memtables a reader sees — the active one and the immutables — published with one volatile write. From M5 the tables come from a retained `Version` |
| pin / pinned | `lock`, `hold` (for a file) | A reference taken (`retain`) on a `Version` or an SSTable so it is not closed or deleted while a reader uses it |
| weakly consistent cursor | `snapshot cursor` (it is not one) | A cursor that pins its sources but may see writes made after it opened; the M4–M6 behaviour (SH-03) |
| failure model | `crash type` | The kind of failure a test or claim covers: clean restart, damaged bytes, torn tail, process death, simulated or physical power loss (ADR-0024 §7) |
| write / read / space amplification | `overhead`, `bloat` | Bytes written to disk per byte written by the user / sources or bytes read per lookup / disk bytes per live byte (the RUM tradeoff) |
| **ShaleDB** (D1+, ADR-0013) | | *The relational layer above the engine* |
| `Catalog` | `Dictionary`, `SchemaRegistry`, `Metadata` | ShaleDB's registry of table and index descriptors, stored in the system keyspace. Never the engine's `Manifest` |
| `TableDescriptor` / `IndexDescriptor` | `TableInfo`, `TableMeta`, `Schema` alone | The catalog entry for one table / one index |
| `Row` | `Tuple`, `Record` in identifiers | One table row: `Value`s in column order |
| `PrimaryIndex` / `SecondaryIndex` | `ClusteredIndex`, `Lookup` | The row-holding index keyed by primary key / an index keyed by other columns ending in the primary key |
| order-preserving encoding (`OrderedEncoding`) | `memcomparable`, `sortable`, `lexi` encoding | Encoding whose bytewise order equals value order |
| `AccessPath` | `ScanType`, `Strategy` | How a plan reads a table: point get, range scan, index scan, table scan (Selinger) |
| `Operator` | `Node`, `Executor` for one operator | One Volcano iterator in a physical plan: `open` / `next` / `close` |
| `LogicalPlan` / `PhysicalPlan` | `QueryTree`, `Plan` alone | Bound relational algebra / the chosen operator tree |
| `Transaction` | `Txn` in identifiers | A ShaleDB unit of serializable work (D4) |
| `ReadSet` / `WriteSet` | `Reads`, `Buffer` | Keys and ranges a transaction read / mutations it will commit |
| `Session` | `Connection` (server side) | One client's state on the server: current transaction, settings |
| `ShaleDbServer` | `Server` alone, `Node` | The ShaleDB server process speaking the PostgreSQL protocol (D5) |
| compaction debt | `backlog`, `pending work` | Bytes a write will cost later in compaction; RocksDB calls the engine-wide total *pending compaction bytes* |

`Node` and `Server` are banned as bare type names because they are the words people reach for by
reflex and mean nothing specific: a plan has `Operator`s, the process is `ShaleDbServer`.

When you add a concept, add it here in the same commit.

---

## 2. Acronyms

Google Java Style says treat acronyms as words: `HttpClient`, not `HTTPClient`. We
follow that — **with one deliberate exception.**

**Rule:** acronyms are PascalCase like ordinary words.

```java
WalWriter        // not WALWriter
LsmTree          // not LSMTree
MvccSnapshot     // not MVCCSnapshot
CrcMismatchException
FprCalculator
```

**Exception:** `SSTable` keeps its canonical casing, everywhere, always.

```java
SSTableReader    // not SstableReader
SSTableWriter
SSTableId
```

Rationale: "SSTable" is not an acronym expanded in prose — it is a proper noun in this
field, written that way in the Bigtable paper, LevelDB, RocksDB, Cassandra, HBase, and
*Database Internals*. Rendering it `Sstable` makes the codebase grep-incompatible with
every reference you will read alongside it. One documented exception is cheaper than a
permanent impedance mismatch with the literature.

There is exactly one exception. Do not add a second without an ADR.

---

## 3. Packages

Root: `dev.shale`. Substitute your own reverse-DNS if you own one; choose once, it is hard to
reverse.

```
dev.shale.wal            dev.shale.db.type          ← shale-db (D1–D6)
dev.shale.memtable       dev.shale.db.record
dev.shale.sstable        dev.shale.db.catalog
dev.shale.compaction     dev.shale.db.table
dev.shale.filter         dev.shale.db.sql
dev.shale.manifest       dev.shale.db.plan
dev.shale.iterator       dev.shale.db.exec
dev.shale.env            dev.shale.db.txn
dev.shale.internal       dev.shale.db.cost
  ← not public API       dev.shale.server           ← shale-server (D5)
                         dev.shale.server.pgwire
                         dev.shale.demo             ← shale-demo (D7)
```

**Package by feature, not by layer.** No `util`, `helpers`, `common`, `misc`, `base`,
or `impl` packages. A class named `WalUtils` is a signal that behaviour belongs on a
type that does not exist yet. If a helper genuinely has no home, that is a design
question, not a naming one.

**`internal` is a real boundary.** Anything under `dev.shale.internal` or a
`*.internal` subpackage may change without notice and is excluded from the compatibility
promise. Everything else in `shale-core` is public API — treat signature changes there
as requiring an ADR.

Every package has a `package-info.java` stating: what it owns, its threading model, and
its entry-point type. Not optional; it is the first thing a reader (human or agent)
looks at.

---

## 4. Types

- Interfaces are named for the role, not decorated: `Compactor`, not `ICompactor` or
  `CompactionInterface`.
- Do not name the implementation `XxxImpl`. Name it for how it differs:
  `LeveledCompactor`, `TieredCompactor`, `SkiplistMemtable`, `ArtMemtable`.
  `XxxImpl` is an admission that you expect exactly one implementation, in which case
  you did not need the interface.
- Abstract bases: `AbstractIterator`. Rare — prefer composition.
- Builders: `SSTableWriter.Builder`, nested, `build()` returns the target type.
- Exceptions end in `Exception`. See `errors-and-logging.md`.
- Test classes: `<TypeUnderTest>Test`, `<TypeUnderTest>PropertyTest`,
  `<Area>CrashTest`, `<Area>ModelTest`, `<Type>Benchmark`.

## 5. Methods

- Getters have no `get` prefix for domain values: `sequenceNumber()`, `keyCount()`,
  `blockHandle()`. Reserve `getX()` for genuine JavaBean interop, which we have none of.
- Boolean queries read as assertions: `isImmutable()`, `hasTombstone()`,
  `containsKey()`, `mayContain()` — note `mayContain()` for the bloom filter, which
  correctly signals the false-positive semantics that `contains()` would hide.
- Methods that perform I/O or can block say so or declare it: `flushAsync()`,
  `awaitFlush()`, `syncBlocking()`. A method whose name does not suggest I/O must not
  do I/O.
- Factory methods: `of()`, `from()`, `open()`, `create()`. Use `open()` for anything
  acquiring a file handle or arena, so its resource nature is visible at the call site.
- Reference counting is always `retain()` / `release()`. Never `acquire()`, `ref()`,
  `close()` for this purpose.

## 6. Variables and constants

- Byte offsets and sizes carry units: `offsetBytes`, `blockSizeBytes`,
  `flushThresholdBytes`. Never a bare `size` on anything measured in bytes.
- Durations carry units: `compactionTimeoutMillis`, `electionTimeoutMillis`.
- Constants are `UPPER_SNAKE_CASE` and grouped in the type they configure, not in a
  global `Constants` class.
- On-disk magic numbers and format versions live in the format's own class as
  `MAGIC` and `FORMAT_VERSION`, next to a comment giving their byte layout.
- No single-letter names except loop indices `i`, `j` and standard math in a
  well-commented algorithm. `k`/`v` for key/value are banned — write `key`, `value`.

## 7. Files and directories

- On-disk artifacts follow LevelDB convention so they are recognisable:
  `000123.sst`, `000124.wal`, `MANIFEST-000007`, `CURRENT`, `LOCK`.
- File numbers are zero-padded to *at least* six digits (`%06d`; number 1,000,000 has seven),
  allocated from one monotonic counter shared across all file kinds. One counter, never per-kind.
  Names are matched as `\d{6,}` and ordered by number, never as strings (SH-09).
- Documentation files are `kebab-case.md`. ADRs are `NNNN-kebab-title.md`.
- Every package that defines a byte layout has a `format.md` beside its source.
