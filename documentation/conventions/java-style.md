# Java style

Baseline: **Google Java Style**, enforced by Spotless with
`googleJavaFormat()`, 100-column lines, 2-space indent. Formatting is not a matter of
opinion here — run `./gradlew spotlessApply` and move on. What follows is the part a
formatter cannot enforce.

Naming is covered separately in `naming.md`.

---

## 1. Dependency allowlist

The prime directive (see `CLAUDE.md` §1) is that core mechanisms are hand-written.
This is the enforcement mechanism.

**`shale-core` runtime dependencies: none.** The engine compiles against the JDK and
nothing else. This is not an aspiration; it is checked in CI.

**Permitted, `shale-core` test scope only:**
`junit-jupiter`, `assertj-core`, `jqwik` (property-based testing), `jmh-core` and
`jmh-generator-annprocess` (in `shale-bench`).

**Permitted, `flotilla-*` runtime:** exactly one RPC stack (gRPC + protobuf, or Netty
if hand-rolling the protocol — decided in an ADR, not ad hoc), plus SLF4J as a logging
*facade* with a binding chosen only at the server entry point.

**Explicitly banned everywhere:**

| Banned | Because |
|---|---|
| Guava | Contains a bloom filter, a cache, and `Ordering` — three project subjects |
| Caffeine, Ehcache | The block cache is a project subject |
| Protobuf/Kryo/Avro **for on-disk formats** | The SSTable and WAL encodings are the exercise (protobuf for *RPC* is fine) |
| Any Raft library (jraft, Atomix, Ratis, Copycat) | Consensus is a project subject |
| Any embedded KV store (RocksDB JNI, MapDB, Xodus, LMDB bindings) | That is the whole project |
| Lombok | Hides the constructors, equals, and field mutability that this codebase is specifically about making visible |
| Spring, Guice, any DI container | Wiring is done by hand in a composition root; the object graph should be readable |
| `sun.misc.Unsafe` | Use the Foreign Function & Memory API (`Arena`, `MemorySegment`) |

Adding anything requires an ADR that answers: what project subject does this touch, and
what do we lose by not writing it ourselves?

---

## 2. Language level

Target **JDK 25**. Use the modern language where it reduces ceremony, not for novelty.

**Use:**
- `record` for value types: `BlockHandle`, `VersionEdit`, `InternalKey` views.
  Records give you correct `equals`/`hashCode`/`toString` and immutability by default,
  which is exactly what the on-disk-descriptor types need. Validate in the compact
  constructor.
- `sealed interface` + records for closed hierarchies: value types
  (`Put`/`Delete`/`RangeDelete`), compaction outcomes, Raft messages. Then `switch`
  patterns are exhaustive and the compiler catches the missing case when you add one.
- Pattern-matching `switch` over visitor patterns and `instanceof` chains.
- `Arena` / `MemorySegment` for all off-heap memory and memory-mapped files. Never
  `MappedByteBuffer` for new code — the 2 GiB `int`-indexing limit and the
  non-deterministic unmapping are exactly the problems `MemorySegment` fixes.
- Virtual threads for the RPC/connection layer in `flotilla-server`.

**Do not use:**
- Virtual threads for compaction, flush, or any CPU-bound background work. Those want
  a bounded platform-thread pool with explicit sizing — that is a tuning knob, and
  pinning behaviour under `synchronized` is a trap. Use them for I/O-bound
  connection handling only.
- Streams on hot paths (per-key iteration, block decoding, merge). Streams allocate
  and obscure control flow; a plain loop is faster and, in a merge iterator, clearer.
  Streams in setup, configuration, and tests are fine.
- Optional as a field or parameter type. Return type only.
- Checked exceptions for anything but genuine caller-recoverable conditions —
  see `errors-and-logging.md`.
- Reflection, dynamic proxies, annotation-driven magic. Everything should be
  traceable by reading.

---

## 3. Immutability and construction

- Fields are `final` unless there is a stated reason otherwise, and that reason is a
  concurrency contract (see `concurrency-and-resources.md`).
- No setters on domain types. If a type needs to change, either it is a mutable
  component with an explicit thread owner, or you want a new instance.
- Constructors validate and do not perform I/O. Anything touching the filesystem goes
  in a static `open()` that returns a closeable type — so resource acquisition is
  visible at the call site rather than hidden in a `new`.
- More than four constructor parameters means a builder or a config record.

---

## 4. Bytes

This codebase is mostly about bytes, so these are load-bearing.

- **All on-disk and on-wire integers are little-endian**, matching LevelDB and the
  native order of every platform we target. Never rely on platform default; state the
  order at every read/write site via an explicit `ByteOrder.LITTLE_ENDIAN` layout.
- Variable-length integers use LevelDB's varint32/varint64 encoding. One
  implementation, in `dev.shale.internal.coding`, used everywhere.
- Never use `String` for keys or values in engine code. Keys are `MemorySegment` or
  `byte[]` with explicit offset and length. `String` appears only in APIs, tests, and
  logging, and the conversion is always explicit with a stated charset (UTF-8).
- Never use `byte[].equals()` or `==` on key bytes. All comparison goes through the
  configured `Comparator`, which is a first-class, persisted component (its name is
  written to the manifest so a database cannot be reopened with an incompatible one).
- Slices do not copy. A method returning a view of a buffer says so in its name or
  Javadoc and states the view's validity lifetime. Accidentally retaining a slice past
  its arena's close is the most likely source of a JVM crash in this project.

---

## 5. Javadoc

Required on every public type and every non-obvious public method. Private methods get
comments only when the *why* is unclear; do not narrate the *what*.

Every core type carries:

```java
/**
 * Immutable, sorted on-disk table. Layout is documented in {@code format.md}
 * alongside this package.
 *
 * <p><b>Threading:</b> immutable and safe for concurrent readers after
 * {@link #open}. Reference-counted; callers must {@link #retain()} before
 * handing to another thread and {@link #release()} when done.
 *
 * <p><b>Ownership:</b> holds a file descriptor and a mapped {@link
 * java.lang.foreign.MemorySegment} scoped to the arena passed to {@code open}.
 *
 * @see <a href="https://github.com/google/leveldb/blob/main/doc/table_format.md">
 *      LevelDB table format</a>
 * @see "Petrov, Database Internals, ch. 7 — deviation: we store the filter
 *      block handle in the footer rather than the metaindex, see ADR-0007"
 */
```

The four required elements: what it is, **Threading**, **Ownership**, and a citation
with any deviation called out. The citation requirement (`CLAUDE.md` N9) is what turns
this repository from code into a readable study of the field.

---

## 6. Method and class shape

- A method that does not fit on one screen is doing several things. Compaction and
  recovery loops are the legitimate exceptions; extract the inner steps anyway.
- Guard clauses over nested conditionals. Maximum nesting depth 3.
- No boolean parameters on public methods — `flush(true)` is unreadable. Use an enum:
  `flush(Durability.SYNC)`.
- Return empty collections and iterators, never `null`. `null` return is permitted only
  for "key not found" on the lowest-level lookup path, where it is documented and where
  boxing an `Optional` per lookup would be measurable.
- Package-private is the default visibility for anything not deliberately public API.
  `public` in `shale-core` is a promise.

---

## 7. Banned APIs

Enforced by Checkstyle (`config/checkstyle/checkstyle.xml`):

- `System.out`, `System.err`, `printStackTrace()` — use the logger.
- `Thread.sleep` in `src/main` and in tests — use the injected `Clock`.
- `new Random()` without a seed — every random source is seeded and the seed is logged.
- `System.currentTimeMillis()`, `System.nanoTime()`, `Instant.now()` directly — take a
  `Clock`. Determinism in tests depends on this, and later so does simulation testing.
- `String.format` on hot paths — use parameterised logging.
- Star imports.
- `sun.misc.Unsafe`, `sun.nio.*`, anything under `com.sun`.
- `finalize()`, `Runtime.addShutdownHook` for correctness-critical cleanup.
- `Object.wait/notify` — use `java.util.concurrent`.
