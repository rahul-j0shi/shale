# M0 as-built — `shale-core` type graph and model harness

The detail behind the built (green) boxes in the
[README overview](../../README.md#architecture--the-complete-project-scope). Everything here
is in the code at tag `m0-skeleton`; see the [M0 release note](../roadmap/m0-release-note.md).

## The `shale-core` type graph (all 17 types)

Real `implements` / `extends` / `uses` edges and the concurrency annotation on each type.

```mermaid
flowchart TB
  subgraph api["dev.shale — public API"]
    sb["StorageBackend — interface<br/>put · delete · get · scan · comparator · close"]
    cur["Cursor — interface · @NotThreadSafe<br/>isValid · next · key · value · close"]
    dur["Durability — enum · @Immutable<br/>NONE · SYNC · GROUP"]
    br["ByteRange — record · @Immutable<br/>(array, offset, length) · of()"]
    kc["KeyComparator — interface<br/>compare(ByteRange,ByteRange) · name · compare(byte[],byte[])"]
    bwc["BytewiseComparator — final class, singleton · @ThreadSafe<br/>INSTANCE · unsigned lexicographic"]
    se["ShaleException — abstract class"]
    ce["CorruptionException<br/>offsetBytes · expectedValue · actualValue"]
    stx["StorageException"]
    ese["EngineStateException"]
  end

  subgraph ann["dev.shale.internal.annotations"]
    marks["@ThreadSafe · @NotThreadSafe · @Immutable"]
  end
  subgraph cod["dev.shale.internal.coding"]
    le["LittleEndian — final class · @ThreadSafe<br/>putFixed64 · getFixed64"]
  end
  subgraph key["dev.shale.internal.key"]
    vt["ValueType — enum · @Immutable<br/>DELETE=0x00 · PUT=0x01 · FOR_SEEK · fromCode"]
    ikey["InternalKey — record · @Immutable<br/>userKey · sequenceNumber(56b) · valueType<br/>MAX_SEQUENCE · packTrailer · encode · decode"]
    ikc["InternalKeyComparator — final class · @ThreadSafe<br/>userKey asc, trailer desc"]
  end

  ac["AutoCloseable (JDK)"]
  rt["RuntimeException (JDK)"]

  sb -->|extends| ac
  cur -->|extends| ac
  bwc -->|implements| kc
  ikc -->|implements| kc
  ce -->|extends| se
  stx -->|extends| se
  ese -->|extends| se
  se -->|extends| rt

  sb -.uses.-> dur
  sb -.uses.-> cur
  sb -.uses.-> kc
  kc -.uses.-> br
  ikey -.uses.-> le
  ikey -.uses.-> vt
  ikey -.uses.-> ce
  ikc -.uses.-> br
  ikc -.uses.-> le
  vt -.uses.-> ce
  ann -. "one marker per type" .-> api
  ann -.-> cod
  ann -.-> key
```

Per-type coverage (29 cases): `ByteRangeTest`, `BytewiseComparatorTest` + `…PropertyTest`,
`CorruptionExceptionTest`, `LittleEndianPropertyTest`, `ValueTypeTest`, `InternalKeyTest` +
`…PropertyTest`, `InternalKeyComparatorTest`.

## The model harness (`dev.shale.model`)

The one executable flow at M0: a reference backend built on the real encoding, diffed against
a `TreeMap` oracle.

```mermaid
flowchart TB
  seeds["Seeds.resolve()<br/>-Dshale.test.seed or SecureRandom"]
  smt["StorageBackendModelTest · @Tag(model)<br/>5000 seeded ops: 70% put / 30% delete"]
  rnd["java.util.Random(seed)"]
  backs["Backends<br/>drain(Cursor) · assertMatches (AssertJ)"]
  refb["ReferenceBackend implements StorageBackend<br/>TreeMap keyed by InternalKey.encode()<br/>ordered by InternalKeyComparator"]
  refm["ReferenceModel — oracle<br/>TreeMap ordered by BytewiseComparator"]
  ikstack["InternalKey · InternalKeyComparator · ValueType<br/>(encode/decode · ceilingEntry lookup · tombstones)"]
  hst["HarnessSelfTest"]
  buggy["BuggyBackend.ignoringDeletes()"]

  seeds --> smt
  smt --> rnd
  smt -->|"put/delete (Durability.NONE)"| refb
  smt -->|"same ops"| refm
  smt -->|"after every 100 ops + final"| backs
  backs -->|"get(probeKeys) + full scan"| refb
  backs -->|compare| refm
  refb -.uses.-> ikstack
  hst -->|wraps| buggy
  buggy -->|delegates puts, drops deletes| refb
  hst -->|"assertMatches must throw"| backs
```

`ReferenceBackend` is the only thing that actually exercises the M0 encoding: it stores
encoded `InternalKey`s in a `TreeMap` ordered by `InternalKeyComparator`, resolves a `get`
via a `FOR_SEEK`/`MAX_SEQUENCE` ceiling lookup, and hides tombstones on `scan`. The oracle
is a plain `TreeMap`; the harness asserts they never diverge, and `HarnessSelfTest` proves
the assertion bites by running a backend that ignores deletes.
