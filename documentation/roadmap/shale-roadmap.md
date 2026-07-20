# Building an LSM-Tree Storage Engine in Java: Charter, Component Map, and Roadmap

## TL;DR
- **Build it, and build it full-stack.** A hand-written LSM engine in Java, taken all the way to a Raft-replicated, sharded distributed layer, is one of the highest-signal systems portfolio projects you can attempt — it forces you to confront durability, crash consistency, the read/write/space amplification trilemma (the RUM conjecture), and consensus, which very few "build a database" tutorials do end to end. Scope it as ~8–11 milestones over many months, each producing a runnable, testable artifact.
- **On the B+Tree question: do NOT build a full comparative B+Tree backend as your primary path — use B-Tree/B+Tree ideas *inside* the engine (block indexes) now, and build an optional copy-on-write B+Tree backend late as a "capstone" comparison.** The highest learning value is: (1) a block index inside every SSTable (mandatory), and (2) — only after the LSM path works — a small LMDB-style copy-on-write B+Tree as a second `StorageBackend` behind a shared interface so you can benchmark LSM vs. in-place. Building it first or as a co-equal track roughly doubles your surface area for little added insight.
- **Sequence it strictly bottom-up:** WAL + in-memory map → immutable memtable/flush → SSTable format → multi-way merge reads → compaction → bloom filters → MVCC/snapshots → manifest/recovery hardening → then the distributed layer (single-Raft-group replication → multi-Raft sharding → Percolator transactions). Each layer depends on the one below; get durability and recovery correct before you optimize.

## Key Findings

### Why this is an exceptional learning project
An LSM engine is unusually dense with "deep systems" concepts that don't appear in typical CRUD/web projects: append-only durability, crash recovery/replay, immutable file lifecycle with reference counting, background compaction with write-stall backpressure, probabilistic data structures (bloom filters), MVCC via sequence numbers, and multi-way merge iteration. Because Alex Petrov's *Database Internals* is split into Part I (Storage Engines: B-Trees, file formats, transactions/recovery, B-Tree variants, log-structured storage) and Part II (Distributed Systems: failure detection, leader election, replication/consistency, anti-entropy, distributed transactions, consensus), your project maps almost 1:1 onto the book — you can use it as your spine.

"Complete control over a storage engine" conceptually buys you the ability to *choose your position on the RUM conjecture*: as stated verbatim by Athanassoulis, Kester, Maas, Stoica, Idreos, Ailamaki & Callaghan in "Designing Access Methods: The RUM Conjecture" (EDBT 2016), *"An access method that can set an upper bound for two out of the read, update, and memory overheads, also sets a lower bound for the third overhead."* LSM trees deliberately trade read and space amplification for low write amplification; B-Trees do the opposite. Controlling the engine means controlling those knobs (compaction policy, bloom bits-per-key, block size, memtable size) yourself.

### The RUM conjecture and amplification (the intellectual core)
- **Read amplification (RA):** bytes/lookups read vs. bytes the user wanted. In a naive LSM, a point lookup may probe the memtable + every level; LevelDB worst case touches L0 files plus one file per deeper level.
- **Write amplification (WA):** bytes written to disk per byte of user data. Leveled compaction WA is roughly the per-level fanout summed over levels (commonly >10×); tiered/universal is much lower.
- **Space amplification (SA):** on-disk size vs. logical size. Leveled minimizes SA; tiered can transiently *double* space during compaction.
- Mark Callaghan's "CRUM" extension adds **cache amplification** as a fourth axis. These are the metrics you will instrument and benchmark.

### B+Tree recommendation (detailed answer in Details §D)
Petrov covers copy-on-write B-Trees (LMDB), lazy B-Trees (WiredTiger), FD-trees, and Bw-trees (the latch-free, delta-chain, log-structured B-tree used in Microsoft Hekaton) in Chapter 6, and LSM/log-structured storage in Chapter 7. B+Trees legitimately appear *inside* real LSM engines as SSTable block indexes, and copy-on-write B+Trees are the canonical *contrast* backend (in-place, read-optimized). Recommendation: use B+Tree ideas internally now; build a small COW B+Tree comparison backend as a late capstone, not up front.

## Details

### A. Project charter — the "why", goals, non-goals, success criteria

**Mission statement (suggested):** *"Build, by hand and in Java, a durable, crash-consistent, LSM-tree key-value storage engine and then a Raft-replicated, range-sharded distributed store on top of it — in order to understand, at the granular level, every mechanism modern databases (RocksDB, Cassandra, TiKV, CockroachDB) rely on."*

**Explicit goals**
1. Correctness first: never lose or corrupt acknowledged data across crashes; pass property-based, crash/fault-injection, and model-based tests.
2. Conceptual completeness: implement each core component by hand at least once (WAL, memtable, SSTable, compaction, bloom filter, MVCC, manifest, Raft, sharding).
3. Observability: expose metrics for the amplification factors so tradeoffs are measurable.
4. A written design doc per milestone recording reversible vs. irreversible decisions.

**Non-goals (keep it tractable) — put these OUT of scope initially**
- SQL/query planner, secondary indexes, a full type system — stay a byte-key/byte-value store.
- Columnar storage, HTAP, vectorized execution.
- A production network server, auth, multi-tenancy, TLS.
- Byzantine fault tolerance (Raft assumes crash-stop, non-Byzantine nodes).
- Key-value separation (WiscKey), learned indexes, ribbon/cuckoo filters, open-channel SSD/FTL work — note them as "stretch" only.
- Portable/zero-copy on-disk format guarantees across architectures beyond fixing endianness.

**Success criteria (thresholds)**
- Milestone-level: each stage ships with a green test suite including a crash-recovery test.
- Engine: survives `kill -9` + restart with zero acknowledged-write loss under `sync=true`; a random operation history checked against an in-memory `TreeMap` reference model shows zero divergences.
- Distributed: a 3-node cluster tolerates 1 node failure with no data loss and continues serving; ideally a Jepsen-style linearizability check passes for single-key ops.

**Realistic complexity/time expectations (per phase, for one developer learning as they go)**
- WAL + memtable + basic get/put: small (days–2 weeks).
- SSTable format + flush + merged reads: medium (2–4 weeks) — the file format is fiddly.
- Compaction (leveled or tiered): medium-hard (3–6 weeks) — concurrency and write stalls are the hard part.
- Bloom filters, block cache, MVCC/snapshots: medium (each 1–3 weeks).
- Manifest/version-set + robust recovery: medium-hard and easy to underestimate.
- Raft (from scratch): hard (1–2 months); election + log replication + snapshotting + membership changes each add difficulty. MIT 6.824 labs are the standard proving ground.
- Sharding + multi-Raft + a placement/metadata service: hard (1–2+ months).
- Percolator-style distributed transactions: hard (weeks).

Total: this is a multi-quarter to year-long endeavor at a hobby pace. That is expected and fine.

### B. Complete component inventory (single-node engine)

**Write-Ahead Log (WAL).** Append-only durability log written before the memtable is updated so acknowledged writes survive crashes. Design points: record format (length + CRC32 checksum + type + payload); segment files with rotation (like Kafka's rolled, immutable segments with a single active segment); fsync policy (`FileChannel.force(false)`); **group commit** — batch many writers into one fsync to amortize its fixed cost. Per Facebook's RocksDB "WAL Performance" wiki, *"all outstanding writes that qualify to be combined will be combined together and write to WAL once, with one fsync ... The maximum group size is 1MB. RocksDB won't try to increase batch size by proactive delaying the writes."* (PostgreSQL achieves the same via `commit_delay`/`commit_siblings`.) Crash recovery = replay the WAL into a fresh memtable; truncate/delete WAL segments only after the covered memtable is flushed to an SSTable. Expose `sync=true/false` (durability vs. throughput): per RocksDB's documented semantics, *"When sync = true, a write is not considered committed until the data is fsync()ed to storage,"* whereas **RocksDB defaults to `sync = false`, meaning WAL writes are not crash-safe by default** — a deliberate performance trade-off that accepts a small crash-loss window.

**MemTable.** In-memory sorted buffer receiving writes after the WAL. Canonical implementation is a **skiplist** (LevelDB/RocksDB/HBase all use skiplists; Java's `ConcurrentSkipListMap` is a ready concurrent option). Skiplists are chosen over balanced trees because their structural modifications are simple and concurrency is tractable (no rebalancing). Alternatives to know: single-threaded vs. concurrent skiplist (RocksDB added concurrent memtable inserts with a spinlock-guarded arena and per-core buffers), hash+skiplist, and adaptive radix tree (ART). Use **arena allocation** to cut GC pressure and pointer overhead — RocksDB's skiplist lives on an arena; Pebble's arena is fixed-size and uses offsets instead of pointers. On fill, switch the active memtable to an **immutable memtable** (handoff), open a new mutable one, and flush the immutable one in the background. Track memory accounting to trigger switches.

**SSTable (Sorted String Table) file format.** Immutable, sorted on-disk table. Study the LevelDB `table_format`: a sequence of **data blocks** (default ~4KB), then meta/**filter blocks** (bloom), a **metaindex block**, an **index block** (one entry per data block: a separator key ≥ last key in that block → BlockHandle{offset,size}), and a fixed-size **footer**. Per google/leveldb's `table_format.md` and `table/format.h`, the footer is 48 bytes total — two block handles with padding `char[40-p-q]` (40 == 2×`BlockHandle::kMaxEncodedLength`) plus a `fixed64` magic number `== 0xdb4775248b80fb57` (little-endian); the source comment notes the magic *"was picked by running `echo http://code.google.com/p/leveldb/ | sha1sum` and taking the leading 64 bits."* Inside a data block, keys use **prefix compression** with **restart points** every N keys (default 16) storing the full key, plus a restart-offset array for binary search. Keys are **internal keys** = user key + (sequence number, value type) packed together, sorted by user key ascending then seqno descending. Add per-block CRC and optional block compression. Version the format via the magic number.

**Compaction.** Background merge of SSTables to bound read/space amplification and drop obsolete data. Taxonomy (per RocksDB wiki): **Classic Leveled** (O'Neil; each level one sorted run, fanout ~10×, minimizes SA at cost of WA), **Tiered** (RocksDB calls it *Universal*; multiple runs per level, low WA, higher RA/SA, can transiently 2× space), **Tiered+Leveled hybrid** (RocksDB's leveled is really this — L0 tiered, deeper levels leveled), **Leveled-N**, and **FIFO** (time-series, just drop oldest). Advanced: **Dostoevsky's Lazy Leveling** (leveling only at the largest level, tiering elsewhere) and its Fluid LSM design space. Implementation concerns: triggers/scoring (L0 file count, per-level size vs. target), picking overlapping files, **subcompactions** for parallelism, throttling/**write stalls** (RocksDB `soft/hard_pending_compaction_bytes_limit`), **trivial move** optimization (move a file to the next level without rewriting when key ranges don't overlap — gives near-2× WA for sequential inserts). Make compaction **tombstone-aware** and handle **range tombstones**.

**Bloom filters.** Per-SSTable (or partitioned per-block) probabilistic set-membership filters that let a lookup skip a table that definitely lacks the key. Sizing: bits-per-key sets false-positive rate. Key optimization: **Monkey** (Dayan, Athanassoulis & Idreos, SIGMOD 2017), whose core insight is verbatim that *"worst-case lookup cost is proportional to the sum of the false positive rates of the Bloom filters across all levels of the LSM-tree"* — so allocating *more* bits to smaller/shallower levels and fewer to the largest level (instead of uniform bits-per-key) cuts lookup latency; the paper reports Monkey *"reduces lookup latency by an increasing margin as the data volume grows (50%−80% for the data sizes we experimented with)."* Alternatives to mention: ribbon filters, cuckoo filters, prefix bloom filters (for range/prefix scans).

**Tombstones.** Deletion markers written like normal entries (a value-type flag). Needed because deletes must shadow older values in deeper levels. **Range tombstones** delete a key range in one record. Problems: they cause **read amplification** (must be checked on reads) and the **resurrection problem** — if a tombstone is compacted away before the value it shadows reaches the same level, the deleted key can reappear; hence tombstones must survive until they've been compacted past all older data, and for distributed systems a **grace period / GC TTL** is added. Per Apache Cassandra's docs, *"The grace period for a tombstone is set with the table property WITH gc_grace_seconds. Its default value is 864000 seconds (ten days), after which a tombstone expires and can be deleted during compaction."*

**Manifest / VERSION / version sets.** LevelDB/RocksDB-style metadata: a **MANIFEST** log of **version edits** (files added/removed per level, sequence numbers, comparator name) describing the current **version** (the set of live SSTables). New versions install **atomically** by appending an edit and pointing a CURRENT file at the manifest. This is how the engine knows, after restart, which files constitute the database. Combine with **reference counting** so files in use by an iterator/snapshot aren't deleted until safe.

**Iterators.** A read merges the memtable, immutable memtable(s), and all relevant SSTables via a **heap-based multi-way merge** (min-heap keyed on internal key). Supports seek, prefix iteration, and **snapshot isolation** (skip entries with seqno > snapshot). Reconciliation returns the newest version of each user key and hides tombstones.

**Caching.** Three layers: **block cache** (uncompressed/compressed data blocks; LRU or CLOCK or TinyLFU), **table cache** (open file handles + index/filter blocks), and the **OS page cache** (interacts with mmap vs. buffered reads). Know that mmap reads defer caching to the OS.

**MVCC / sequence numbers / snapshots / transactions.** A monotonically increasing global **sequence number** stamped into each internal key gives multi-versioning. A **snapshot** is just a seqno; reads ignore newer versions. Older versions are dropped during compaction once no snapshot needs them. Support **atomic write batches** (all-or-nothing group of mutations sharing behavior in the WAL). Transactions can be optimistic (validate at commit) or pessimistic (locking); start simple with atomic batches + snapshots.

**Key encoding / comparators.** Internal key = user key + 8-byte packed (56-bit seqno, 8-bit type). A pluggable **comparator** defines ordering; store its name in the manifest to prevent opening a DB with an incompatible comparator. Use varints for lengths (LevelDB style).

**Recovery/startup path.** Read CURRENT → replay MANIFEST to reconstruct the version set → replay WAL segments newer than the last flush into a memtable → resume. This is the most correctness-critical path; test it relentlessly.

**Statistics/metrics & observability.** Counters for bytes written/read at each layer (to compute WA/RA/SA), compaction bytes, stalls, cache hit rates, bloom FPR, per-op latencies. Without these you cannot reason about the RUM tradeoffs you're supposedly learning.

**Space reclamation / file lifecycle.** Reference-counted SSTables; delete a file only when it's no longer in any live version and no iterator/snapshot references it.

### C. Distributed layer

**Replication.** Start with leader–follower **log shipping**: replicate the operation log (the Raft log), not the physical SSTables. Replicating operations (logical) is simpler and engine-agnostic; replicating the WAL/files (physical) couples replicas to byte formats. Note the "double logging" problem — a Raft log plus an engine WAL means writes are logged twice; production research (e.g., PASV/Nezha) tries to unify them, but keep them separate for clarity first.

**Consensus (Raft).** Diego Ongaro & John Ousterhout's Raft ("In Search of an Understandable Consensus Algorithm," USENIX ATC 2014) decomposes into **leader election** (randomized election timeouts, `RequestVote`), **log replication** (`AppendEntries`, which doubles as heartbeat; commit once a majority acknowledges), and **safety** (leader completeness: a leader must hold all committed entries; leaders never overwrite their own log). Add **snapshotting/log compaction** (Raft's log can't grow forever; nodes snapshot committed state and discard the prefix; `InstallSnapshot` RPC catches up lagging followers) — and note the important interaction: **the Raft snapshot of a key-value state machine is essentially the LSM engine's own state**, so you can implement Raft snapshots by referencing a consistent engine snapshot rather than serializing separately. Add **membership changes** (joint consensus or single-server changes; note a 2014 safety bug in single-server changes and that **Pre-Vote** and **leadership transfer** are standard production extensions). Add **linearizable reads** via **ReadIndex** or **lease reads** to avoid routing every read through a full log round-trip.

**Sharding / partitioning.** **Range partitioning** (contiguous key ranges → "regions") supports range scans and is the better learning path; **hash partitioning / consistent hashing** (Dynamo/Cassandra) spreads load but breaks range scans. Per TiKV's docs, its region-split-size defaults to 96MiB with a region-max-size of 144MiB — *"Large regions more than 144MB ... will be split into two or more regions with 96MB each,"* with a region-split-keys threshold of 960000 keys — a reasonable model for your own split thresholds. Implement **partition split/merge/rebalance**, a **routing layer**, and a **placement driver / metadata service** (TiKV's PD tracks node/store/region metadata and hands out timestamps). Run **one Raft group per shard** ("multi-Raft") so leaders are spread across nodes.

**Distributed transactions.** The clearest path is **Percolator** (Google, OSDI 2010) as adopted by TiKV: a **timestamp oracle (TSO)** issues monotonic `startTS`/`commitTS`; 2PC uses one of the transaction's keys as the primary/coordinator (avoiding a separate coordinator SPOF); locks/writes live in separate column families (Percolator's data/lock/write columns). Alternatives to know: classic 2PC/3PC, Calvin (deterministic), Spanner (TrueTime), and timestamp mechanisms **HLC** (hybrid logical clocks) vs. centralized **TSO** vs. **TrueTime**.

**Leaderless / anti-entropy alternative (Dynamo/Cassandra).** Instead of consensus, use quorum replication (R+W>N for strong-ish consistency), **read repair**, **hinted handoff**, **Merkle trees** for anti-entropy, and conflict resolution via **vector clocks** or **last-write-wins**. Cassandra's newer **Accord** (leaderless, timestamp/dependency-based transactions) is where that lineage is heading.

**Which distributed path to choose for learning:** **Go the consensus (Raft) + range-sharding route (the TiKV/CockroachDB/etcd model), not the leaderless Dynamo route.** Reasons: (1) Raft gives you strong consistency you can *test* for (linearizability), which is pedagogically cleaner than reasoning about eventual-consistency conflict resolution; (2) it composes naturally with your LSM engine as the replicated state machine; (3) it's the model Petrov's consensus chapter and MIT 6.824 both teach, so you'll have abundant guidance. Treat leaderless/anti-entropy as a "read about it, maybe prototype read-repair" stretch.

**CAP/PACELC & failure detection.** Frame your consistency choices with CAP and PACELC (else, latency vs. consistency even when no partition). Implement **failure detection** via heartbeats plus either **phi-accrual** (adaptive, continuous suspicion level; used in Cassandra/Akka) or **SWIM** (gossip-based, infection-style dissemination, UDP, round-robin probing; used in many membership systems).

**RPC/network layer in Java.** gRPC + Protocol Buffers over HTTP/2 is the pragmatic choice (it's what TiKV uses) and gives you schema-evolved messages for free; Netty is the lower-level alternative if you want to hand-roll a protocol. Consider **virtual threads (Project Loom)** to write the many-connection server code in a simple blocking style.

### D. B+Tree recommendation (explicit)

**Question recap:** should the B+Tree be (a) a separate comparative backend, (b) only internal SST index structures, or (c) something else?

**Answer: primarily (b) now, with (a) as a deliberately-scoped *late capstone* — a hybrid, staged approach. Do not make it a co-equal track from the start.**

Reasoning:
1. **B+Tree ideas belong inside the LSM engine regardless.** Every SSTable has a **block index** that is effectively a one-level (or, with partitioned indexes, multi-level) B+Tree-like sorted index mapping separator keys to block handles. Building that teaches you the read side of B-trees (binary search over sorted separators, fence pointers) with none of the in-place-update pain. This is unavoidable and high value.
2. **A full standalone B+Tree backend is a large, largely orthogonal project.** In-place B-trees bring page splits/merges, rebalancing, a page/buffer manager, and crash-safe in-place updates (WAL redo/undo or shadow paging) — Petrov's Chapters 2–5 are essentially this. Doing it *first* or *in parallel* roughly doubles your scope and delays the LSM insights you set out to get.
3. **But a comparison backend has real payoff — done at the right time.** Once your LSM path works and you have a `StorageBackend` interface plus benchmarks (YCSB/db_bench-style), implementing a compact **copy-on-write (LMDB-style) B+Tree** as a second backend is the single best way to *feel* the RUM conjecture: you'll measure LSM's superior write throughput and B+Tree's superior read latency/space efficiency and predictable (compaction-free) tail latency on the same harness. Choose **copy-on-write** specifically because it sidesteps the hardest correctness problem (crash-safe in-place page updates) using page shadowing/MVCC — LMDB readers never block writers, uses two B+trees (one for data, one for the free-page list), and per the literature copy-on-write/page-shadowing gives roughly 2× lower write-amp than in-place B-tree updates. It's the elegant contrast to append-only LSM.
4. **Know, but don't build, the exotic variants:** the **Bw-tree** (latch-free, delta-chain, log-structured B-tree; Hekaton's ordered index; famously under-specified and hard — the CMU "Building a Bw-Tree Takes More Than Just Buzz Words" paper documents this) and **Bε-trees** (buffered internal nodes) show the B-tree/LSM design space is a continuum, not a binary.

**Concrete recommendation:** Implement the SSTable block index now (mandatory). Define a `StorageBackend` interface early. After the LSM engine is stable (post-milestone 7), spend a bounded 2–4 weeks on a copy-on-write B+Tree backend purely for benchmarking. Skip the in-place/Bw-tree rabbit holes unless you specifically want to study concurrency control.

**Reversible vs. hard-to-change:** the `StorageBackend` interface and comparator/key-encoding are hard to change later (design carefully). Which compaction strategy, bloom sizing, block size, and cache policy you pick are easily reversible/tunable.

### E. Sequencing / roadmap (milestone build order)

Each milestone yields a working, testable artifact. Dependencies flow downward.

- **M0 — Skeleton & interfaces.** `byte[] get/put/delete`, a `StorageBackend` interface, a comparator, internal-key encoding, and a test harness that diffs against a `TreeMap` reference. *(Interface decisions here are the expensive-to-reverse ones.)*
- **M1 — WAL + in-memory map.** Append-only log with CRC + segments; recover by replay; `sync` toggle. Now you're durable. *Depends on M0.*
- **M2 — MemTable + immutable handoff.** Replace the map with a skiplist; add memory accounting + memtable switching. *Depends on M1.*
- **M3 — SSTable write + flush.** Serialize an immutable memtable to an SSTable (data blocks, restart points, block index, footer). Reads now check memtable then one SSTable. *Depends on M2.*
- **M4 — Multi-SSTable reads + merge iterator.** Heap-based multi-way merge across memtable + many SSTables; reconciliation + tombstones. *Depends on M3.*
- **M5 — Manifest + recovery hardening.** Version edits, atomic version install, CURRENT file, reference-counted file lifecycle, full crash-recovery tests. *Depends on M4.*
- **M6 — Compaction.** Start with size-tiered (simpler) or leveled; add scoring, file picking, background threads, write stalls, trivial move. *Depends on M5.*
- **M7 — Bloom filters + block/table cache + MVCC/snapshots.** Per-SSTable bloom (then Monkey-style allocation as a stretch); block cache; sequence-number snapshots + atomic write batches. *Depends on M6.*
- **M8 — (Capstone comparison) COW B+Tree backend + full benchmark suite (YCSB/db_bench-style, JMH microbenchmarks).** *Depends on M7 + the M0 interface.*
- **M9 — Single Raft group replication.** Engine becomes the replicated state machine behind Raft (election, log replication, snapshot = engine snapshot). *Depends on M7.*
- **M10 — Multi-Raft sharding + placement/metadata service + routing.** Range partitions, split/merge, PD-like metadata + TSO. *Depends on M9.*
- **M11 — Percolator distributed transactions.** 2PC with primary-key coordinator, TSO timestamps, lock/write column families. *Depends on M10.*

### F. Java-specific considerations

- **Off-heap memory.** Prefer the **Foreign Function & Memory API (Project Panama)** — `Arena` + `MemorySegment` — finalized in **Java 22 (JEP 454)**. It replaces `sun.misc.Unsafe` and gives safe, deterministic (arena-scoped) off-heap allocation with **64-bit addressing** (`MemorySegment` uses `long` offsets, so no 2GB cap) and bounds checking. Use it for arenas (memtable), off-heap block cache, and mmap.
- **Memory-mapped files.** Legacy `MappedByteBuffer` is capped at 2GB (int-indexed) and has unmap/GC-timing issues; the modern path is `FileChannel.map(..., arena)` returning a `MemorySegment` you can unmap deterministically by closing the arena.
- **GC pressure.** The whole point of arenas/off-heap and byte-slice reuse is to avoid per-entry object churn. Represent keys/values as segments/slices, not `String`/boxed objects, on hot paths. Consider a low-pause collector (ZGC/Shenandoah) for the server.
- **Durability semantics.** `FileChannel.force(false)` = fsync data without necessarily flushing all metadata; understand that only after force returns is data durable, and that a single device cache FLUSH benefits all writers to that device (basis for group commit).
- **Direct I/O.** The JVM has no first-class `O_DIRECT`; you generally rely on the page cache or mmap, or use JNI/native for direct I/O. Note this limitation rather than fighting it early.
- **Virtual threads (Loom).** Great for the RPC/server layer (blocking-style code, huge connection counts). Not a substitute for careful compaction/flush thread-pool design.
- **Benchmarking.** Use **JMH (Java Microbenchmark Harness)**, built by the OpenJDK team, for hot-path microbenchmarks — it correctly handles JVM warmup/JIT steady state, prevents dead-code elimination (via `Blackhole.consume()`) and constant folding, and runs `@Fork`ed JVMs for isolation. Naive `System.nanoTime()` loops can appear many times faster than reality due to DCE. Use **YCSB** (Yahoo Cloud Serving Benchmark, Java-based; workloads **A** update-heavy 50/50, **B** read-mostly 95/5, **C** read-only, **D** read-latest, **E** short range scans, **F** read-modify-write) for macro comparisons, mirroring RocksDB's **db_bench** operations (`fillseq`, `fillrandom`, `readrandom`, `readwhilewriting`, `seekrandom`).

**JVM reference engines to study**
- **Apache Cassandra** — the canonical production Java LSM; its docs state the architecture *"is based on Log Structured Merge (LSM) trees, which utilize an append-only approach instead of the traditional relational database design with B-trees."* Study its commit log (WAL), memtable, SSTables, per-SSTable bloom filters, and multiple compaction strategies (size-tiered, leveled, date-tiered).
- **Apache HBase** — Bigtable-style LSM on HDFS: WAL + MemStore → HFile, MVCC, region splitting, and "Accordion" in-memory compaction (*"The topmost is a mutable in-memory store, called MemStore… Once a MemStore overflows, it is flushed to disk, creating a new HFile."*).
- **Apache Lucene** — not KV but the best study of **immutable "write-once" segments + logarithmic tiered merge** (`TieredMergePolicy`), directly analogous to compaction and near-real-time refresh vs. fsync.
- **RocksDB Java bindings (`org.rocksdb:rocksdbjni`)** — mature LSM API surface (Options, column families, WriteBatch) and a model for JNI bridging; per the RocksJava docs, *"rocksdbjni.jar contains the Java classes that defines the Java API for RocksDB, while librocksdbjni.so includes the C++ rocksdb library and the native implementation."*
- **JetBrains Xodus** — pure JVM append-only log storage (`.xd` files) + B+tree/Patricia indexing + MVCC/snapshot isolation + log GC.
- **MapDB** — approachable off-heap/paged storage, serialization, WAL, collections-over-storage (concurrent Maps/Sets/Queues backed by disk or off-heap memory).
- **Chronicle Map** — off-heap, memory-mapped, segment-locked hash KV; great for off-heap/mmap/GC-free techniques (note: weak durability — data is guaranteed on disk only when the map is closed).
- **Apache Kafka log** — cleanest segmented append-only log (rolling, retention, compaction) — a direct WAL model (single active segment; rolled to read-only when full).
- **`dain/leveldb`** — a readable pure-Java port of LevelDB whose stated goal is *"a feature complete implementation that is within 10% of the performance of the C++ original"* (memtable, SSTable, log reader/writer, compaction); the closest small Java LSM to read end-to-end (note: only lightly tested and not recently maintained — for learning, not production).

### G. Learning resources & reference implementations

**Key papers**
- O'Neil, Cheng, Gawlick, O'Neil, "The Log-Structured Merge-Tree (LSM-Tree)," *Acta Informatica* 33(4):351–385, 1996 — the origin.
- Chang et al., "Bigtable" (OSDI 2006); DeCandia et al., "Dynamo" (SOSP 2007) — the two lineages.
- Ongaro & Ousterhout, "In Search of an Understandable Consensus Algorithm (Raft)" (USENIX ATC 2014) + Ongaro's dissertation "Consensus: Bridging Theory and Practice."
- Athanassoulis et al., "Designing Access Methods: The RUM Conjecture" (EDBT 2016); Callaghan's CRUM extension (Small Datum blog).
- Dayan, Athanassoulis, Idreos, "Monkey: Optimal Navigable Key-Value Store" (SIGMOD 2017) and the journal version "Optimal Bloom Filters and Adaptive Merging for LSM-Trees" (TODS 2018).
- Dayan & Idreos, "Dostoevsky" (SIGMOD 2018) — lazy leveling / Fluid LSM.
- Lu, Pillai, Arpaci-Dusseau & Arpaci-Dusseau, "WiscKey: Separating Keys from Values in SSD-Conscious Storage" (FAST 2016).
- Luo & Carey, "LSM-based Storage Techniques: A Survey," *The VLDB Journal* 29(1):393–418, 2020 (arXiv:1812.07527, DOI 10.1007/s00778-019-00555-y) — the best single overview; read this early.
- Peng & Dabek, Google "Percolator" (OSDI 2010); Huang et al., "TiDB: A Raft-based HTAP Database" (VLDB 2020); Zhou et al., "FoundationDB" (SIGMOD 2021).
- Sears & Ramakrishnan, "bLSM" (SIGMOD 2012); the Bw-tree papers (Levandoski et al.) + CMU's "Building a Bw-Tree Takes More Than Just Buzz Words."

**Reference codebases known for readability**
- **LevelDB** (Google, C++, small and very readable — the reference to imitate; start from its `doc/table_format.md`).
- **RocksDB** (C++, production LSM; its wiki is a goldmine on compaction/tuning).
- **Pebble** (Go, CockroachDB's RocksDB replacement; excellent docs contrasting with RocksDB).
- **BadgerDB** (Go, WiscKey-style KV separation).
- **sled** (Rust, Bw-tree-inspired).
- **mini-lsm** (Rust tutorial by Chi Zhang / skyzh — "build an LSM in a week," with a compaction simulator and an MVCC week; even if you code in Java, follow its chapter structure).
- **`dain/leveldb`** (Java port) for Java-idiomatic reference.

**Courses / tutorial series**
- **mini-lsm** (skyzh.github.io/mini-lsm) — closest thing to a guided build; mirror its milestones in Java. Its day-by-day arc (block encoding → SST → memtable/merge iterators → WAL/recovery → compaction → bloom filter + key compression → MVCC) is essentially the M1–M8 order above.
- **CMU 15-445/645 (Intro to Database Systems)** and **15-721 (Advanced)** — Andy Pavlo's lectures cover storage, indexing, MVCC, compaction; BusTub is the teaching codebase.
- **MIT 6.824 (Distributed Systems)** — the Raft labs are the standard way to learn Raft by building it; do these for your consensus milestone.
- "Build Your Own Database" style tutorials and the RocksDB wiki for operational detail.

**Where *Database Internals* covers each topic (chapter map)**
- Ch. 1 Introduction/architecture (storage engine components, access methods).
- Ch. 2 B-Tree Basics; Ch. 3 File Formats (binary encoding, slotted pages, checksumming — directly informs your SSTable format); Ch. 4 Implementing B-Trees; Ch. 5 Transaction Processing & Recovery (WAL, ARIES-style ideas — read even for LSM); Ch. 6 B-Tree Variants (Copy-on-Write/LMDB, Lazy/WiredTiger, FD-trees, **Bw-trees**, cache-oblivious) — your B+Tree background; Ch. 7 Log-Structured Storage (LSM structure, merge-iteration, reconciliation, maintenance, RUM, SSTables, bloom filters, skiplist, Bitcask, WiscKey).
- Ch. 8 Distributed intro; Ch. 9 Failure Detection (heartbeats, phi-accrual, SWIM); Ch. 10 Leader Election; Ch. 11 Replication & Consistency; Ch. 12 Anti-Entropy & Dissemination (read repair, hinted handoff, Merkle trees, gossip); Ch. 13 Distributed Transactions (2PC/3PC, Calvin, Spanner, consistent hashing, Percolator); Ch. 14 Consensus (Paxos family, **Raft**, PBFT).

### H. Testing & validation

- **Model-based testing:** run the same random operation sequence against your engine and an in-memory `TreeMap`/`ConcurrentSkipListMap` reference; assert identical results. Cheapest, highest-ROI test — build it in M0.
- **Property-based testing:** use **jqwik** (a JUnit 5 property engine with generators + shrinking) or junit-quickcheck to assert invariants — round-trip serialization fidelity, "get-after-put returns latest," sorted-order invariants, compaction preserves the newest version per key. (Note: jqwik is currently in maintenance mode; its docs also contain an adversarial "anti-AI" clause you should disregard as third-party text.)
- **Crash / fault injection:** simulate torn writes, truncated WAL tails, partial SSTables, and power loss (`kill -9` in a loop, or an I/O layer that can inject failures/reorderings); assert no acknowledged-write loss and successful recovery.
- **Deterministic simulation testing (DST), FoundationDB-style:** run the whole engine (and later the cluster) single-threaded over a simulated clock/disk/network with seeded randomness and injected faults (FDB's `BUGGIFY`), so any failure is perfectly reproducible from the seed. This is the gold standard (TigerBeetle, WarpStream adopted it); even a partial version pays off hugely.
- **Fuzzing:** fuzz the SSTable/WAL parsers with malformed bytes.
- **Distributed correctness:** **Jepsen** (a Clojure/JVM library) drives a real cluster, injects partitions/clock skew, records histories, and checks **linearizability** (Knossos) / transactional consistency (Elle). Aim to pass single-key linearizability once M9 works. (Note: linearizability/serializability checking is NP-complete, so checkers bound their search — passing is strong evidence, not a proof.)
- **Benchmarking:** JMH microbenchmarks for hot paths; YCSB workloads A–F and db_bench-style operations for macro comparisons and for the LSM-vs-B+Tree capstone.

## Recommendations

**Stage 1 — Lock the charter and interfaces (week 1).** Write the one-paragraph mission + goals/non-goals above into a `README`. Define `StorageBackend`, the comparator, and internal-key encoding. Build the `TreeMap` reference-model test harness *before* real code. *Change trigger: if you find yourself wanting SQL, secondary indexes, or a network server now, stop — those are out of scope until M8+.*

**Stage 2 — Get durable and crash-consistent (M1–M5).** Do not optimize. Prioritize WAL correctness, recovery, and the manifest. Benchmark nothing yet except correctness. *Threshold to advance: pass a `kill -9`-in-a-loop crash test with zero acknowledged-write loss under `sync=true`, and zero divergence vs. the reference model over 1M random ops.*

**Stage 3 — Make it a real LSM (M6–M7).** Implement size-tiered compaction first (simpler), then leveled; add bloom filters and a block cache; add MVCC snapshots. Now turn on metrics and measure WA/RA/SA. *Threshold: sustained write throughput stays stable (no unbounded L0 growth / permanent write stall) under a continuous `fillrandom` load — i.e., compaction keeps up.*

**Stage 4 — Prove the tradeoffs (M8).** Build the COW B+Tree backend and run YCSB A–F + JMH across both backends. Write up the RUM tradeoff you measured. This is the portfolio centerpiece — a graph of LSM vs. B+Tree write/read/space is worth more than any README claim. *Bounded to ~2–4 weeks; if it balloons, ship the comparison with in-place-update deferred.*

**Stage 5 — Go distributed (M9–M11).** Do MIT 6.824's Raft labs (in Go) first or in parallel to internalize Raft, then implement it in Java with your engine as the state machine. Add multi-Raft range sharding + a PD-like metadata/TSO service, then Percolator transactions. *Threshold: a 3-node cluster survives 1-node failure with no data loss; single-key linearizability holds under a Jepsen-style partition test.*

**Cross-cutting:** keep a design-decision log tagging each choice **reversible** (compaction policy, bloom bits, block/memtable size, cache policy) vs. **hard-to-change** (`StorageBackend` API, key encoding, comparator identity in the manifest, on-disk format/magic). Invest review time proportionally.

## Caveats
- **Effort estimates are rough** and assume a hobby/learning pace for one person; Raft and the sharding layer in particular commonly take longer than expected. They are illustrative, not commitments.
- **Some cited numbers are workload- and configuration-specific:** leveled-vs-tiered amplification factors, Monkey's 50–80% lookup-latency improvement, WiscKey's speedups, RocksDB's 1MB group-commit cap, TiKV's 96MiB/144MiB region sizes, and Cassandra's 10-day `gc_grace_seconds` default all come from specific papers/docs/benchmarks and will differ on your hardware, workload, and configuration. Treat them as directional defaults.
- **The RUM conjecture is a conjecture, not a proven theorem** — widely observed, not universally proven; use it as a design lens, not a law.
- **Raft is crash-fault-tolerant, not Byzantine-tolerant**, and its single-server membership change had a known safety bug historically — implement membership changes carefully and prefer joint consensus or well-tested single-server variants with Pre-Vote.
- **Java specifics move fast:** the FFM API only finalized in Java 22; on older JDKs you'll fall back to `ByteBuffer`/`Unsafe`/`MappedByteBuffer` with their 2GB and lifecycle limits. Confirm your target JDK before committing to Panama APIs.
- **`dain/leveldb` and some tutorials are lightly maintained/tested** — excellent for reading and learning structure, not for copying into anything you'd rely on.
- **The JVM reference-engine descriptions are summary-level** (some were gathered via a research subagent); read each project's own docs/source before relying on specific implementation details.