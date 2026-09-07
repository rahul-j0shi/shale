# The complete project scope

The whole system, built and planned, in one place. Every box is a component from the
project's own inventory ([`../roadmap/shale-roadmap.md`](../roadmap/shale-roadmap.md)) —
nothing is invented. These four diagrams are the *scope* view: they show where each piece
will sit. For what is actually implemented, read the per-milestone as-built pages indexed in
[README.md](README.md).

**Legend:** solid box / `✓ Mn` = in the code now, built at milestone *Mn*. Dashed box + `Mn`
= defined in the [roadmap](../../README.md#roadmap) and not yet written. Today `shale-core`
is built through M4; `shale-bench`, `flotilla-raft`, and `flotilla-server` are empty build
shells.

### 1 · Modules, dependency direction, and the build gate

```mermaid
flowchart TB
  subgraph repo["shale repo · Gradle 9.6.1 · vendored JDK 25 in .tools/"]
    core["shale-core — LSM engine · JDK-only (N1)<br/>✓ M0-M4: SPI · encoding · WAL · skiplist · SSTable + flush · merge iterator ; M5-M8: manifest · write path · compaction .. B+Tree"]:::part
    bench["shale-bench — JMH / YCSB / db_bench · M8<br/>build shell (no source yet)"]:::plan
    raft["flotilla-raft — consensus · M9<br/>build shell (no source yet)"]:::plan
    server["flotilla-server — RPC / sharding / PD · M10<br/>build shell (no source yet)"]:::plan
  end
  server -->|implementation| raft
  server -->|implementation| core
  raft -->|api| core
  bench -->|jmh| core
  gate["Build gate ✓ — spotless · checkstyle · javac -Werror<br/>tasks: test / crashTest / soakTest · deps: junit / assertj / jqwik / jmh"]:::done
  gate -. enforces .-> core

  classDef done stroke:#2ea043,stroke-width:2px;
  classDef part stroke:#2ea043,stroke-width:2px,stroke-dasharray:6 3;
  classDef plan stroke:#8b949e,stroke-width:1px,stroke-dasharray:4 3;
```

`shale-core` depends on nothing but the JDK; the other three depend inward only — the arrows
are enforced in each module's `build.gradle.kts`, not by convention. `shale-core` is built
through M4 (dashed border: M5-M8 remain); everything else is a shell awaiting its milestone.

### 2 · Shale — the single-node engine (full component scope)

Write path, on-disk format, background work, version/recovery, and read path — every
component from the engine inventory. Only the cross-cutting substrate (SPI, key encoding,
comparators, coding, exceptions) is built; the rest is milestone-tagged.

```mermaid
flowchart TB
  client["client · put / delete / get / scan (Durability)"]:::done

  subgraph xcut["Cross-cutting substrate"]
    spi["StorageBackend · Cursor · Durability ✓ M0"]:::done
    keyz["InternalKey · ValueType · SequenceNumber 56b ✓ M0<br/>KeyComparator · BytewiseComparator ✓ M0"]:::done
    codez["LittleEndian ✓ M0 · varints ✓ M1"]:::done
    obs["Metrics · Clock ✓ M1"]:::done
    errs["Corruption / Storage / EngineState exceptions ✓ M0"]:::done
  end

  subgraph wpath["Write path"]
    wal["WAL ✓ M1<br/>WalSegment · len + CRC32C + type + payload<br/>fsync = DURABILITY point · group-commit batching M2+"]:::done
    mem["Memtable ✓ M2 lock-free skiplist (arena later)"]:::done
    imm["Immutable Memtable(s) ✓ M2 switch · ✓ M3 flush drains to SSTable"]:::done
  end

  subgraph bg["Background workers"]
    flush["Flush ✓ M3 · memtable → SSTable · fsync+rename before WAL delete (D3) · synchronous (bg thread M6)"]:::done
    comp["Compaction M6<br/>leveled / tiered · scoring · file picking · subcompactions<br/>trivial move · write-stall backpressure · Tombstone + RangeTombstone GC"]:::plan
  end

  subgraph store["On disk · NNNNNN.sst"]
    subgraph sstable["SSTable ✓ M3"]
      ft["Footer ✓ M3 · magic · FORMAT_VERSION"]:::done
      idx["IndexBlock ✓ M3 · last key to BlockHandle"]:::done
      mi["MetaIndex ✓ M3 (empty; filter handle M7)"]:::done
      fb["FilterBlock · BloomFilter M7 (Monkey bits/level)"]:::plan
      db["DataBlock ✓ M3 · prefix-compressed · RestartPoint · CRC32C"]:::done
    end
    lvl["Levels L0 .. Ln"]:::plan
  end

  subgraph vers["Version set & file lifecycle M5"]
    edit["VersionEdit"]:::plan
    man["Manifest log + CURRENT"]:::plan
    ver["Version · live SSTable set · reference-counted (retain/release)"]:::plan
  end

  subgraph rpath["Read path"]
    snap["Snapshot = SequenceNumber M7 · atomic WriteBatch M7"]:::plan
    merge["Merge iterator ✓ M4 · min-heap over InternalIterator<br/>reconcile newest-per-key · hide Tombstones · seek to bound"]:::done
    cache["Block cache · Table cache M7 · OS page cache"]:::plan
  end

  bpt["COW B+Tree backend M8 · 2nd StorageBackend (RUM comparison)"]:::plan

  client --> spi
  spi --> wal
  wal --> mem
  mem --> imm
  imm --> flush
  flush --> sstable
  sstable --> lvl
  ft --> idx
  ft --> mi
  idx --> db
  mi --> fb
  flush --> edit
  comp --> edit
  lvl <--> comp
  edit --> man
  man --> ver
  spi --> snap
  snap --> merge
  mem --> merge
  imm --> merge
  ver --> merge
  lvl --> merge
  fb -. skip .-> merge
  cache <--> merge
  merge --> client
  man -. recovery .-> ver
  wal -. replay .-> mem
  keyz -. encodes .-> wal
  keyz -. encodes .-> db
  spi -. alt backend .-> bpt

  classDef done stroke:#2ea043,stroke-width:2px;
  classDef plan stroke:#8b949e,stroke-width:1px,stroke-dasharray:4 3;
```

### 3 · Flotilla — the distributed store (full component scope)

The engine becomes the replicated state machine behind Raft; a router and placement driver
shard the key space into Regions, each its own Raft group. All planned (M9–M10); the state
machine is the M0 engine. Percolator (M11) is a non-goal and is not drawn.

```mermaid
flowchart TB
  client["client"]:::plan
  rpc["RPC M10 · gRPC + protobuf / HTTP2 · virtual threads"]:::plan
  router["Router M10"]:::plan
  pd["Placement Driver / metadata M10<br/>Store and Region registry · split / merge / rebalance · TSO (timestamp oracle)"]:::plan
  fd["Failure detection M10 · heartbeats · phi-accrual / SWIM"]:::plan

  subgraph region["Region = contiguous key range · one RaftGroup (multi-Raft) M10"]
    raft["flotilla-raft M9<br/>leader election (RequestVote, Pre-Vote) · log replication (AppendEntries = heartbeat)<br/>safety · membership (joint consensus) · linearizable reads (ReadIndex / lease)"]:::plan
    subgraph peer["Peer · leader"]
      rlog["Raft log"]:::plan
      sm["State machine = shale-core StorageBackend ✓ M0"]:::done
    end
    fol["Peer · followers (majority commits)"]:::plan
  end

  client --> rpc
  rpc --> router
  router -. locate region .-> pd
  router --> peer
  raft --- peer
  rlog -->|AppendEntries| fol
  fol -->|ack| rlog
  rlog -->|commit on majority| sm
  peer -. "InstallSnapshot = engine Snapshot" .-> fol
  pd -. "rebalance / split / merge" .-> region
  fd -. suspect .-> region

  classDef done stroke:#2ea043,stroke-width:2px;
  classDef plan stroke:#8b949e,stroke-width:1px,stroke-dasharray:4 3;
```

### 4 · Verification & benchmarking (full scope)

```mermaid
flowchart LR
  subgraph tiers["Test tiers (testing.md)"]
    unit["Unit ✓ M0"]:::done
    modelt["Model vs TreeMap ✓ M0"]:::done
    prop["Property · jqwik ✓ M0"]:::done
    crash["Crash · FaultyFileSystem M5"]:::plan
    soak["Soak M6"]:::plan
    dst["Deterministic simulation · seeded M9"]:::plan
    fuzz["Fuzzing · WAL/SSTable parsers M3+"]:::plan
    jep["Jepsen linearizability M9+"]:::plan
  end
  bench["Benchmarks · shale-bench<br/>JMH plugin wired, no benchmarks written yet · YCSB A-F · db_bench (fillseq/fillrandom/readrandom/seekrandom) M8<br/>RUM: LSM vs COW B+Tree M8"]:::plan
  target["Shale engine + Flotilla cluster"]
  tiers --> target
  bench --> target

  classDef done stroke:#2ea043,stroke-width:2px;
  classDef plan stroke:#8b949e,stroke-width:1px,stroke-dasharray:4 3;
```

### 5 · As-built detail (per milestone)

The finer-grained diagrams of what actually exists in the code — the `shale-core` type graph,
the model harness, and each milestone's internals — are indexed in
[README.md](README.md), organised by milestone so they grow with the code.
