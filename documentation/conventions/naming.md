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
| `Version` | `Snapshot` (reserved, see below) | The live set of SSTables at a point in time |
| `Snapshot` | `Checkpoint` | An MVCC read view, identified by a sequence number |
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
dev.shale.internal       dev.shale.db.txn
  ← not public API       dev.shale.db.cost
                         dev.shale.server           ← shale-server (D5)
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
- File numbers are zero-padded to six digits, allocated from one monotonic counter
  shared across all file kinds. One counter, never per-kind.
- Documentation files are `kebab-case.md`. ADRs are `NNNN-kebab-title.md`.
- Every package that defines a byte layout has a `format.md` beside its source.
