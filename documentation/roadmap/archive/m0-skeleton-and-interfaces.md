# M0 — Skeleton & Interfaces: Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: use `superpowers:subagent-driven-development`
> (recommended) or `superpowers:executing-plans` to implement this plan task-by-task.
> Steps use checkbox (`- [ ]`) syntax for tracking. Every task is one commit on a branch
> cut from `main` (`commits.md` §5). Run `source scripts/env.sh` once per shell so
> `./gradlew` uses the vendored JDK 25.

**Goal:** Establish the hard-to-reverse core contracts of the engine — the
`StorageBackend` SPI, the pluggable byte-key comparator, and the internal-key encoding —
and the `TreeMap`-based model test harness that will validate every later milestone,
without writing any real storage mechanism yet.

**Architecture:** `shale-core` gains a small set of dependency-free public interfaces and
value types plus an in-memory *reference* backend that lives in test scope. The reference
backend is keyed by encoded internal keys and assigns sequence numbers, so it genuinely
exercises the M0 encoding/ordering code while behaving like a sorted map. A seeded,
randomised model harness diffs any `StorageBackend` against a `TreeMap` oracle after every
operation; a self-test proves the harness detects divergence.

**Tech Stack:** Java 25 (`record`, `sealed`, pattern `switch`), Gradle 9.6.1, JUnit 5,
jqwik (property tests), AssertJ. No runtime dependencies in `shale-core` (N1).

---

## 1. What M0 is, and what it is deliberately not

M0 is **skeleton and interfaces** (roadmap §E). The roadmap is explicit that the
decisions made here — the `StorageBackend` interface, the comparator identity, and the
internal-key encoding — are the *expensive-to-reverse* ones (roadmap §D, §E). Everything
else in the engine is downstream of these three, so M0 gets them right and writes them
down as ADRs before any code.

**In scope (this plan):**

- Public API surface: `StorageBackend`, `Cursor`, `Durability`, `KeyComparator`,
  `BytewiseComparator`, and the exception hierarchy.
- Internal-key machinery: `ValueType`, `InternalKey` (encode/decode + trailer packing),
  `InternalKeyComparator`, and the little-endian fixed-integer coder they depend on.
- Concurrency annotations (`@ThreadSafe` / `@NotThreadSafe` / `@Immutable`).
- The model test harness: `ReferenceModel` (oracle), `ReferenceBackend` (the M0 artifact
  the harness drives), the diff routine, the seeded model test, and a harness self-test.
- Three ADRs (0004 key encoding, 0005 endianness, 0006 `StorageBackend` SPI) and backfill
  of the two accepted-but-missing ADR files (0001, 0003).

**Explicitly deferred (do NOT build in M0 — say so if a task seems to need them):**

| Deferred | Arrives at | Why not now |
|---|---|---|
| WAL, real durability, `Durability` semantics | M1 | Nothing is persistent yet; the `Durability` param exists to lock the API shape (N3), not to act. |
| Injected `Clock` | M1 | No M0 code path reads time; adding it now is speculative (`java-style.md` §7 requires it once time is used). |
| `Metrics` interface + emitters | M1 | No M0 operation produces a measurement; the first counters are `wal.*` (`errors-and-logging.md` §3). |
| Hand-written skiplist memtable | M2 | Memtable is a project subject (N1); the M0 reference backend is a test oracle, not the engine. |
| On-disk formats, `format.md`, golden files, CRC32C | M3 | M0 defines the internal-key *encoding* but writes nothing to disk. |
| `FaultyFileSystem`, crash tests | M5 | Nothing to crash yet. |
| Snapshots, `WriteBatch`, MVCC read views | M7 | Sequence numbers exist in the encoding; user-visible snapshots do not. |

The M0 "working, tested artifact" (roadmap requires one per milestone) is: the encodings
and comparator are implemented and property-tested; the model harness runs green against
the reference backend; and the harness self-test proves it catches a seeded bug.

---

## 2. Decisions to ratify as ADRs (write these first, in Task 2)

Our own convention (`commits.md` §5.2) says a branch anchored to hard-to-reverse work
opens with the ADR, not code. Three ADRs gate this milestone. Their decisions are fixed
below so execution transcribes rather than re-derives them.

**ADR-0004 — Internal key encoding** (`Reversible: no`). Matches `on-disk-formats.md` §5.
- `InternalKey := userKey (bytes) || trailer`, where `trailer = (sequenceNumber << 8) | valueTypeCode`, written as a **fixed 8-byte little-endian** integer appended after the user key.
- `sequenceNumber` is 56 bits ⇒ `MAX_SEQUENCE = 2^56 − 1`; a larger value is an `IllegalArgumentException` (caller bug).
- Sort order: user key **ascending** by the configured `KeyComparator`, then trailer **descending** (newest sequence first, so a seek lands on the newest version). Getting this backwards returns stale data while passing casual tests — call it out.
- `valueTypeCode`: `DELETE = 0x00`, `PUT = 0x01`; `0x02–0xFE` reserved (reject on decode), `0xFF` illegal. Lookups are built with `FOR_SEEK = PUT` and `MAX_SEQUENCE`.

**ADR-0005 — Little-endian fixed-width integers** (`Reversible: no`). Matches
`on-disk-formats.md` §2. Every fixed-width integer that will ever be written to disk or
the wire is little-endian, stated explicitly at each site via the `LittleEndian` coder;
never inherited from platform default. M0's only such integer is the internal-key trailer.

**ADR-0006 — `StorageBackend` SPI shape** (`Reversible: no` — it is the seam every backend
implements, roadmap §D). Byte-key / byte-value only; `get` returns `null` for absent (the
one documented `null`-return path, `java-style.md` §6); every acknowledging write takes an
explicit `Durability` (N3); ordered iteration via `Cursor` with half-open `[from, to)`
bounds; the backend exposes its `KeyComparator` (whose `name()` will be persisted in the
manifest at M5 so an incompatible comparator refuses to open a database).

Also in Task 2: **backfill** `documentation/adr/0001-record-architecture-decisions.md`
and `documentation/adr/0003-single-repo-four-modules.md`, which the ADR index already
lists as `Accepted` but whose files are missing.

---

## 3. File structure

All paths under `shale-core/`. Source root `src/main/java/dev/shale/…`, tests
`src/test/java/dev/shale/…`.

```
src/main/java/dev/shale/
├── package-info.java                     public API: front door of the engine
├── StorageBackend.java                   the SPI (ADR-0006)
├── Cursor.java                           ordered iteration cursor
├── Durability.java                       NONE | SYNC | GROUP (N3)
├── ByteRange.java                        (array, offset, length) view for comparators
├── KeyComparator.java                    pluggable, named byte comparator
├── BytewiseComparator.java               default unsigned-lexicographic comparator
├── ShaleException.java                   abstract root (unchecked)
├── CorruptionException.java              bad bytes (N4)
├── StorageException.java                 environment failure
├── EngineStateException.java             engine cannot serve now
└── internal/
    ├── annotations/
    │   ├── package-info.java
    │   ├── ThreadSafe.java
    │   ├── NotThreadSafe.java
    │   └── Immutable.java
    ├── coding/
    │   ├── package-info.java
    │   └── LittleEndian.java             fixed64 LE put/get (ADR-0005)
    └── key/
        ├── package-info.java
        ├── ValueType.java                DELETE/PUT + reserved range (ADR-0004)
        ├── InternalKey.java              record + encode/decode + trailer (ADR-0004)
        └── InternalKeyComparator.java    userkey asc, trailer desc

src/test/java/dev/shale/
├── internal/coding/LittleEndianPropertyTest.java
├── internal/key/ValueTypeTest.java
├── internal/key/InternalKeyTest.java
├── internal/key/InternalKeyPropertyTest.java
├── internal/key/InternalKeyComparatorTest.java
├── ByteRangeTest.java
├── BytewiseComparatorTest.java
├── BytewiseComparatorPropertyTest.java
└── model/
    ├── ReferenceModel.java               TreeMap oracle
    ├── ReferenceBackend.java             in-memory StorageBackend (drives M0 code)
    ├── BuggyBackend.java                 intentionally wrong, for the self-test
    ├── Backends.java                     drain(Cursor) + assertBackendMatchesModel(...)
    ├── Seeds.java                        seed sourcing + logging
    ├── StorageBackendModelTest.java      @Tag("model") — the harness
    └── HarnessSelfTest.java              proves the harness detects divergence

documentation/adr/
├── 0001-record-architecture-decisions.md   (backfill)
├── 0003-single-repo-four-modules.md         (backfill)
├── 0004-internal-key-encoding.md
├── 0005-little-endian-fixed-integers.md
└── 0006-storage-backend-spi.md
```

Package-by-feature (`naming.md` §3): no `util`/`common`/`impl`. Every package carries a
`package-info.java` stating what it owns, its threading model, and its entry-point type.

---

## 4. Type reference (names are load-bearing — keep them exact across tasks)

```java
// dev.shale (public API)
interface StorageBackend extends AutoCloseable {
  void put(byte[] userKey, byte[] value, Durability durability);
  void delete(byte[] userKey, Durability durability);
  byte[] get(byte[] userKey);                              // null iff absent
  Cursor scan(byte[] fromInclusive, byte[] toExclusive);   // null bound = open-ended
  KeyComparator comparator();
  @Override void close();
}
interface Cursor extends AutoCloseable {
  boolean isValid(); void next(); byte[] key(); byte[] value(); @Override void close();
}
enum Durability { NONE, SYNC, GROUP }
record ByteRange(byte[] array, int offset, int length) { static ByteRange of(byte[] a); }
interface KeyComparator {
  int compare(ByteRange a, ByteRange b);           // 2 params: within ParameterNumber max 4
  String name();
  default int compare(byte[] a, byte[] b) { return compare(ByteRange.of(a), ByteRange.of(b)); }
}
final class BytewiseComparator implements KeyComparator { static final BytewiseComparator INSTANCE; }

abstract class ShaleException extends RuntimeException
class CorruptionException extends ShaleException
class StorageException  extends ShaleException
class EngineStateException extends ShaleException

// dev.shale.internal.coding
final class LittleEndian {
  static void putFixed64(byte[] dst, int offset, long value);
  static long getFixed64(byte[] src, int offset);
}

// dev.shale.internal.key
enum ValueType { DELETE((byte)0x00), PUT((byte)0x01);
  byte code(); static ValueType fromCode(byte code); static final ValueType FOR_SEEK = PUT; }
record InternalKey(byte[] userKey, long sequenceNumber, ValueType valueType) {
  static final long MAX_SEQUENCE = (1L << 56) - 1;
  static long packTrailer(long seq, ValueType type);
  byte[] encode(); int encodedLength(); static InternalKey decode(byte[] encoded);
}
final class InternalKeyComparator implements KeyComparator { InternalKeyComparator(KeyComparator userComparator); }
```

---

## 5. Build-gate conventions (apply to every snippet below)

The build runs Spotless (google-java-format), Checkstyle
(`config/checkstyle/checkstyle.xml`), and `javac -Xlint:all -Werror`. The code snippets in
this plan are written for readability; before committing each task, make them gate-clean:

- **Braces on every control statement.** Checkstyle `NeedBraces` rejects
  `if (x) return y;`. Write `if (x) { return y; }`. The snippets omit braces on one-line
  guards for brevity — add them.
- **Never `System.out` / `System.err` / `printStackTrace()`** — banned in *all* sources,
  tests included. The model harness surfaces its seed in the assertion-failure message,
  not on stdout.
- **Exceptions carry `serialVersionUID`.** `-Werror` promotes the `serial` lint to an
  error, so every `Throwable` subclass declares `private static final long serialVersionUID = 1L;`.
- **`package-info.java` in every package, including test packages.** `JavadocPackage`
  runs on test sources too. Add one (with a one-line package Javadoc) the first time you
  create a file in `dev.shale`, `dev.shale.model`, `dev.shale.internal.coding`, and
  `dev.shale.internal.key` under `src/test/java`.
- **Method parameters ≤ 4** (`ParameterNumber`). This is why the comparator takes
  `ByteRange` views rather than `(array, offset, length)` pairs.
- **Run the gate per task, not just at the end:** after the test passes, run
  `./gradlew spotlessApply` then
  `./gradlew :shale-core:checkstyleMain :shale-core:checkstyleTest --console=plain` and fix
  any finding before committing. Formatting-only fixups go in the same task's commit here
  (they are not separable from first-write); later formatting churn gets its own `style`
  commit (commits.md §3).

---

## Task 1: Add `key` and `coding` commit scopes

**Files:** Modify `documentation/conventions/commits.md:47-48`

`commits.md` §5.2 says "when you add a concept, add it here." M0 introduces two areas
(`key` encoding, `coding`) with no matching scope. Add them so commits below are legal.

- [ ] **Step 1: Edit the scope list** — in the `### Scopes` block, change the list to include `key` and `coding`:

```
`wal`, `memtable`, `sstable`, `compaction`, `filter`, `manifest`, `iterator`, `cache`,
`mvcc`, `recovery`, `key`, `coding`, `api`, `bench`, `raft`, `region`, `rpc`, `pd`,
`docs`, `build`.
```

- [ ] **Step 2: Commit**

```bash
git add documentation/conventions/commits.md
git commit -m "docs(docs): add key and coding commit scopes for M0

Milestone: M0"
```

---

## Task 2: Author the M0 ADRs and backfill the missing index entries

**Files:**
- Create: `documentation/adr/0001-record-architecture-decisions.md`
- Create: `documentation/adr/0003-single-repo-four-modules.md`
- Create: `documentation/adr/0004-internal-key-encoding.md`
- Create: `documentation/adr/0005-little-endian-fixed-integers.md`
- Create: `documentation/adr/0006-storage-backend-spi.md`
- Modify: `documentation/adr/README.md` (index table)

Each ADR follows `documentation/adr/0000-template.md` (Status, Date, Milestone,
Reversible; Context; Options considered — at least two real ones; Decision; Rationale;
Consequences; References). The **decisions** are fixed in §2 above — write them up; do not
change them without stopping and flagging.

- [ ] **Step 1:** Backfill `0001` (Status: Accepted, Reversible: yes) — decision: keep ADRs as described in the ADR README; two options were "ADRs" vs "no formal record". Backfill `0003` (Status: Accepted, Reversible: yes) — decision: single repo, four Gradle modules with the enforced dependency direction; options were multi-repo vs monorepo-single-module vs monorepo-four-modules.
- [ ] **Step 2:** Write `0004` from §2 (ADR-0004 bullets). Include a worked example: user key `0x6B6579` ("key"), sequence `5`, type `PUT` ⇒ trailer `(5<<8)|1 = 0x0501 = 1281`, encoded bytes `6B 65 79 | 01 05 00 00 00 00 00 00`. Reference `on-disk-formats.md` §5, LevelDB `dbformat.h`, Petrov ch. 7.
- [ ] **Step 3:** Write `0005` from §2 (ADR-0005). Reference `on-disk-formats.md` §2, LevelDB `coding.cc`.
- [ ] **Step 4:** Write `0006` from §2 (ADR-0006). Reference roadmap §D (StorageBackend as the M8 comparison seam), `java-style.md` §6 (the one `null`-return path).
- [ ] **Step 5:** Update `documentation/adr/README.md`: set 0004/0005 rows to `Accepted`, add a `0006 | Define the StorageBackend SPI | Accepted | no` row.
- [ ] **Step 6: Commit**

```bash
git add documentation/adr/
git commit -m "docs(docs): record ADRs 0001,0003–0006 for M0 contracts

Backfills the two accepted-but-missing ADR files the index already references,
and ratifies the three hard-to-reverse M0 decisions: internal-key encoding
(0004), little-endian fixed integers (0005), and the StorageBackend SPI (0006).

Milestone: M0
Reversible: no — 0004/0005/0006 fix on-disk-shaped and API contracts
Refs: documentation/conventions/on-disk-formats.md §2,§5; roadmap §D"
```

---

## Task 3: Concurrency annotations

**Files:**
- Create: `shale-core/src/main/java/dev/shale/internal/annotations/{ThreadSafe,NotThreadSafe,Immutable}.java`
- Create: `shale-core/src/main/java/dev/shale/internal/annotations/package-info.java`

Every type declares its threading contract (`concurrency-and-resources.md` §1). These are
three ~5-line `@Documented @Target(TYPE)` markers we own rather than depend on jsr305.

- [ ] **Step 1: Write the three annotations**

```java
// ThreadSafe.java
package dev.shale.internal.annotations;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Target;

/** The annotated type is safe for concurrent use by any thread. */
@Documented
@Target(ElementType.TYPE)
public @interface ThreadSafe {}
```

```java
// NotThreadSafe.java
package dev.shale.internal.annotations;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Target;

/** The annotated type is single-threaded; its Javadoc names the owning thread or role. */
@Documented
@Target(ElementType.TYPE)
public @interface NotThreadSafe {}
```

```java
// Immutable.java
package dev.shale.internal.annotations;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Target;

/** The annotated type has no mutable state reachable after construction. */
@Documented
@Target(ElementType.TYPE)
public @interface Immutable {}
```

- [ ] **Step 2: Write `package-info.java`**

```java
/**
 * Concurrency-contract marker annotations. Every {@code shale-core} type is annotated
 * with exactly one of {@link ThreadSafe}, {@link NotThreadSafe}, or {@link Immutable}
 * (see {@code documentation/conventions/concurrency-and-resources.md} §1).
 *
 * <p><b>Threading:</b> annotations only; no runtime behaviour.
 * <p><b>Entry point:</b> none — applied to other types.
 */
package dev.shale.internal.annotations;
```

- [ ] **Step 3: Compile**

Run: `./gradlew :shale-core:compileJava --console=plain`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

```bash
git add shale-core/src/main/java/dev/shale/internal/annotations/
git commit -m "feat(api): add @ThreadSafe/@NotThreadSafe/@Immutable markers

Milestone: M0
Refs: documentation/conventions/concurrency-and-resources.md §1"
```

---

## Task 4: Exception hierarchy

**Files:**
- Create: `shale-core/src/main/java/dev/shale/{ShaleException,CorruptionException,StorageException,EngineStateException}.java`
- Test: `shale-core/src/test/java/dev/shale/CorruptionExceptionTest.java`

Three categories that callers must respond to differently (`errors-and-logging.md` §1),
under one unchecked abstract root. `CorruptionException` always carries evidence.

- [ ] **Step 1: Write the failing test**

```java
// CorruptionExceptionTest.java
package dev.shale;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class CorruptionExceptionTest {

  @Test
  void message_carriesOffsetAndValues() {
    CorruptionException e =
        new CorruptionException("unknown value type", 12, 1, 255);
    assertThat(e.getMessage()).contains("unknown value type");
    assertThat(e.offsetBytes()).isEqualTo(12);
    assertThat(e.expectedValue()).isEqualTo(1);
    assertThat(e.actualValue()).isEqualTo(255);
    assertThat(e).isInstanceOf(ShaleException.class);
  }
}
```

- [ ] **Step 2: Run — expect compile failure** (types not defined yet)

Run: `./gradlew :shale-core:test --tests 'dev.shale.CorruptionExceptionTest' --console=plain`
Expected: FAIL — `cannot find symbol CorruptionException`

- [ ] **Step 3: Implement the hierarchy**

```java
// ShaleException.java
package dev.shale;

/** Root of the engine's unchecked exception hierarchy (errors-and-logging.md §1). */
public abstract class ShaleException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  protected ShaleException(String message) { super(message); }
  protected ShaleException(String message, Throwable cause) { super(message, cause); }
}
```

```java
// CorruptionException.java
package dev.shale;

/**
 * The bytes read from disk (or a decoded structure) are wrong: checksum mismatch, bad
 * magic, or an impossible value. Callers must stop and escalate — never retry, never
 * repair (N4). Always carries location and evidence (errors-and-logging.md §1).
 *
 * <p>File-aware constructors are added when file readers exist (M3); at M0 the offset is
 * relative to the decoded structure.
 */
public final class CorruptionException extends ShaleException {
  private static final long serialVersionUID = 1L;

  private final long offsetBytes;
  private final long expectedValue;
  private final long actualValue;

  public CorruptionException(String message) {
    this(message, -1, -1, -1);
  }

  public CorruptionException(
      String message, long offsetBytes, long expectedValue, long actualValue) {
    super(message + " (offset=" + offsetBytes
        + " expected=" + expectedValue + " actual=" + actualValue + ")");
    this.offsetBytes = offsetBytes;
    this.expectedValue = expectedValue;
    this.actualValue = actualValue;
  }

  public long offsetBytes() { return offsetBytes; }
  public long expectedValue() { return expectedValue; }
  public long actualValue() { return actualValue; }
}
```

```java
// StorageException.java
package dev.shale;

/** The environment failed: I/O error, disk full, missing file, permission denied. */
public final class StorageException extends ShaleException {
  private static final long serialVersionUID = 1L;

  public StorageException(String message) { super(message); }
  public StorageException(String message, Throwable cause) { super(message, cause); }
}
```

```java
// EngineStateException.java
package dev.shale;

/** The engine cannot serve this request now: closed, failed, read-only, or stalled. */
public final class EngineStateException extends ShaleException {
  private static final long serialVersionUID = 1L;

  public EngineStateException(String message) { super(message); }
  public EngineStateException(String message, Throwable cause) { super(message, cause); }
}
```

- [ ] **Step 4: Run — expect pass**

Run: `./gradlew :shale-core:test --tests 'dev.shale.CorruptionExceptionTest' --console=plain`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add shale-core/src/main/java/dev/shale/ShaleException.java \
        shale-core/src/main/java/dev/shale/CorruptionException.java \
        shale-core/src/main/java/dev/shale/StorageException.java \
        shale-core/src/main/java/dev/shale/EngineStateException.java \
        shale-core/src/test/java/dev/shale/CorruptionExceptionTest.java
git commit -m "feat(api): add ShaleException hierarchy

CorruptionException carries offset/expected/actual so a bad byte is locatable.

Milestone: M0
Refs: documentation/conventions/errors-and-logging.md §1"
```

---

## Task 5: `LittleEndian` fixed-64 coder (ADR-0005)

**Files:**
- Create: `shale-core/src/main/java/dev/shale/internal/coding/LittleEndian.java`
- Create: `shale-core/src/main/java/dev/shale/internal/coding/package-info.java`
- Test: `shale-core/src/test/java/dev/shale/internal/coding/LittleEndianPropertyTest.java`

The one fixed-width integer M0 writes is the internal-key trailer. `putFixed32`/varints
arrive with the WAL (M1); do not add them now (YAGNI).

- [ ] **Step 1: Write the failing property test**

```java
// LittleEndianPropertyTest.java
package dev.shale.internal.coding;

import static org.assertj.core.api.Assertions.assertThat;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import org.junit.jupiter.api.Test;

class LittleEndianPropertyTest {

  @Property
  void putThenGet_roundTrips(@ForAll long value) {
    byte[] buf = new byte[8];
    LittleEndian.putFixed64(buf, 0, value);
    assertThat(LittleEndian.getFixed64(buf, 0)).isEqualTo(value);
  }

  @Test
  void putFixed64_writesLeastSignificantByteFirst() {
    byte[] buf = new byte[8];
    LittleEndian.putFixed64(buf, 0, 0x0102030405060708L);
    assertThat(buf).containsExactly(0x08, 0x07, 0x06, 0x05, 0x04, 0x03, 0x02, 0x01);
  }

  @Property
  void respectsOffset(@ForAll long value) {
    byte[] buf = new byte[16];
    LittleEndian.putFixed64(buf, 5, value);
    assertThat(LittleEndian.getFixed64(buf, 5)).isEqualTo(value);
  }
}
```

- [ ] **Step 2: Run — expect fail** (`cannot find symbol LittleEndian`)

Run: `./gradlew :shale-core:test --tests 'dev.shale.internal.coding.LittleEndianPropertyTest' --console=plain`
Expected: FAIL

- [ ] **Step 3: Implement**

```java
// LittleEndian.java
package dev.shale.internal.coding;

import dev.shale.internal.annotations.ThreadSafe;

/**
 * Little-endian fixed-width integer coding. Every fixed-width integer that will be
 * written to disk or the wire is little-endian, stated explicitly here rather than
 * inherited from a platform default (ADR-0005, on-disk-formats.md §2).
 *
 * <p><b>Threading:</b> stateless; all methods are pure functions over caller-owned arrays.
 */
@ThreadSafe
public final class LittleEndian {
  private LittleEndian() {}

  /** Writes {@code value} as 8 little-endian bytes at {@code dst[offset..offset+7]}. */
  public static void putFixed64(byte[] dst, int offset, long value) {
    dst[offset]     = (byte) value;
    dst[offset + 1] = (byte) (value >>> 8);
    dst[offset + 2] = (byte) (value >>> 16);
    dst[offset + 3] = (byte) (value >>> 24);
    dst[offset + 4] = (byte) (value >>> 32);
    dst[offset + 5] = (byte) (value >>> 40);
    dst[offset + 6] = (byte) (value >>> 48);
    dst[offset + 7] = (byte) (value >>> 56);
  }

  /** Reads 8 little-endian bytes at {@code src[offset..offset+7]} as a long. */
  public static long getFixed64(byte[] src, int offset) {
    return (src[offset]         & 0xFFL)
        | (src[offset + 1] & 0xFFL) << 8
        | (src[offset + 2] & 0xFFL) << 16
        | (src[offset + 3] & 0xFFL) << 24
        | (src[offset + 4] & 0xFFL) << 32
        | (src[offset + 5] & 0xFFL) << 40
        | (src[offset + 6] & 0xFFL) << 48
        | (src[offset + 7] & 0xFFL) << 56;
  }
}
```

```java
// package-info.java
/**
 * Binary integer coding shared by every on-disk and internal structure. Little-endian
 * fixed widths now; LevelDB-style varints arrive with the WAL (M1).
 *
 * <p><b>Threading:</b> stateless utilities. <b>Entry point:</b> {@link LittleEndian}.
 */
package dev.shale.internal.coding;
```

- [ ] **Step 4: Run — expect pass**

Run: `./gradlew :shale-core:test --tests 'dev.shale.internal.coding.LittleEndianPropertyTest' --console=plain`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add shale-core/src/main/java/dev/shale/internal/coding/ \
        shale-core/src/test/java/dev/shale/internal/coding/
git commit -m "feat(coding): add little-endian fixed64 coder

Milestone: M0
Reversible: no — establishes the on-disk integer byte order (ADR-0005)
Refs: documentation/conventions/on-disk-formats.md §2; ADR-0005"
```

---

## Task 6: `ValueType` (ADR-0004)

**Files:**
- Create: `shale-core/src/main/java/dev/shale/internal/key/ValueType.java`
- Test: `shale-core/src/test/java/dev/shale/internal/key/ValueTypeTest.java`

- [ ] **Step 1: Write the failing test**

```java
// ValueTypeTest.java
package dev.shale.internal.key;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import dev.shale.CorruptionException;
import org.junit.jupiter.api.Test;

class ValueTypeTest {

  @Test
  void codes_matchLevelDb() {
    assertThat(ValueType.DELETE.code()).isEqualTo((byte) 0x00);
    assertThat(ValueType.PUT.code()).isEqualTo((byte) 0x01);
  }

  @Test
  void forSeek_isPut() {
    assertThat(ValueType.FOR_SEEK).isEqualTo(ValueType.PUT);
  }

  @Test
  void fromCode_roundTripsKnownCodes() {
    assertThat(ValueType.fromCode((byte) 0x00)).isEqualTo(ValueType.DELETE);
    assertThat(ValueType.fromCode((byte) 0x01)).isEqualTo(ValueType.PUT);
  }

  @Test
  void fromCode_rejectsReservedCode() {
    assertThatThrownBy(() -> ValueType.fromCode((byte) 0x02))
        .isInstanceOf(CorruptionException.class);
  }
}
```

- [ ] **Step 2: Run — expect fail.** Command: `./gradlew :shale-core:test --tests 'dev.shale.internal.key.ValueTypeTest' --console=plain` → FAIL.

- [ ] **Step 3: Implement**

```java
// ValueType.java
package dev.shale.internal.key;

import dev.shale.CorruptionException;
import dev.shale.internal.annotations.Immutable;

/**
 * The low byte of an internal-key trailer: what a record means (ADR-0004). Codes match
 * LevelDB's {@code dbformat.h}. Codes {@code 0x02–0xFE} are reserved for types not yet
 * invented and rejected on decode; {@code 0xFF} is illegal (on-disk-formats.md §5).
 */
@Immutable
public enum ValueType {
  DELETE((byte) 0x00),
  PUT((byte) 0x01);

  /** Lookups are built with the highest type so a seek lands on the newest version. */
  public static final ValueType FOR_SEEK = PUT;

  private final byte code;

  ValueType(byte code) { this.code = code; }

  public byte code() { return code; }

  /** Decodes a trailer's type byte; a reserved or unknown code is corruption (N4). */
  public static ValueType fromCode(byte code) {
    return switch (code) {
      case 0x00 -> DELETE;
      case 0x01 -> PUT;
      default -> throw new CorruptionException(
          "unknown value type", -1, PUT.code, code & 0xFF);
    };
  }
}
```

- [ ] **Step 4: Run — expect pass.** Same command → PASS.

- [ ] **Step 5: Commit**

```bash
git add shale-core/src/main/java/dev/shale/internal/key/ValueType.java \
        shale-core/src/test/java/dev/shale/internal/key/ValueTypeTest.java
git commit -m "feat(key): add ValueType with reserved-code rejection

Milestone: M0
Reversible: no — type codes are part of the internal-key encoding (ADR-0004)
Refs: documentation/conventions/on-disk-formats.md §5; ADR-0004"
```

---

## Task 7: `InternalKey` (ADR-0004)

**Files:**
- Create: `shale-core/src/main/java/dev/shale/internal/key/InternalKey.java`
- Create: `shale-core/src/main/java/dev/shale/internal/key/package-info.java`
- Test: `shale-core/src/test/java/dev/shale/internal/key/InternalKeyTest.java`
- Test: `shale-core/src/test/java/dev/shale/internal/key/InternalKeyPropertyTest.java`

Note: the record has a `byte[]` component, so the default `equals`/`hashCode` compare
array *identity* — wrong for a value type. Override them explicitly (this is the kind of
mutability the codebase insists on making visible, `java-style.md` §3).

- [ ] **Step 1: Write the failing unit test** (worked example from ADR-0004)

```java
// InternalKeyTest.java
package dev.shale.internal.key;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import dev.shale.CorruptionException;
import org.junit.jupiter.api.Test;

class InternalKeyTest {

  @Test
  void encode_appendsLittleEndianTrailer() {
    // user key "key" = 0x6B6579, seq 5, PUT ⇒ trailer (5<<8)|1 = 0x0501
    InternalKey ik = new InternalKey(new byte[] {0x6B, 0x65, 0x79}, 5, ValueType.PUT);
    assertThat(ik.encode())
        .containsExactly(0x6B, 0x65, 0x79, 0x01, 0x05, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00);
  }

  @Test
  void decode_recoversFields() {
    byte[] encoded =
        new InternalKey(new byte[] {1, 2, 3}, 42, ValueType.DELETE).encode();
    InternalKey back = InternalKey.decode(encoded);
    assertThat(back.userKey()).containsExactly(1, 2, 3);
    assertThat(back.sequenceNumber()).isEqualTo(42);
    assertThat(back.valueType()).isEqualTo(ValueType.DELETE);
  }

  @Test
  void construct_rejectsSequenceAboveFiftySixBits() {
    assertThatThrownBy(
            () -> new InternalKey(new byte[] {1}, InternalKey.MAX_SEQUENCE + 1, ValueType.PUT))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void decode_rejectsTooShortInput() {
    assertThatThrownBy(() -> InternalKey.decode(new byte[] {0, 0, 0}))
        .isInstanceOf(CorruptionException.class);
  }

  @Test
  void equals_comparesByValueNotArrayIdentity() {
    assertThat(new InternalKey(new byte[] {9}, 1, ValueType.PUT))
        .isEqualTo(new InternalKey(new byte[] {9}, 1, ValueType.PUT));
  }
}
```

- [ ] **Step 2: Write the failing property test**

```java
// InternalKeyPropertyTest.java
package dev.shale.internal.key;

import static org.assertj.core.api.Assertions.assertThat;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.LongRange;
import net.jqwik.api.constraints.Size;

class InternalKeyPropertyTest {

  @Property
  void encodeDecode_roundTrips(
      @ForAll @Size(max = 64) byte[] userKey,
      @ForAll @LongRange(min = 0, max = (1L << 56) - 1) long seq) {
    InternalKey ik = new InternalKey(userKey, seq, ValueType.PUT);
    InternalKey back = InternalKey.decode(ik.encode());
    assertThat(back.userKey()).containsExactly(userKey);
    assertThat(back.sequenceNumber()).isEqualTo(seq);
    assertThat(back.valueType()).isEqualTo(ValueType.PUT);
  }
}
```

- [ ] **Step 3: Run — expect fail.** Command: `./gradlew :shale-core:test --tests 'dev.shale.internal.key.InternalKey*' --console=plain` → FAIL.

- [ ] **Step 4: Implement**

```java
// InternalKey.java
package dev.shale.internal.key;

import dev.shale.CorruptionException;
import dev.shale.internal.annotations.Immutable;
import dev.shale.internal.coding.LittleEndian;
import java.util.Arrays;

/**
 * user key + (56-bit sequence number, 8-bit value type), the engine's load-bearing key
 * encoding (ADR-0004, on-disk-formats.md §5). Encoded form is
 * {@code userKey || fixed64LE((sequence << 8) | typeCode)}.
 *
 * <p>Sort order (see {@link InternalKeyComparator}): user key ascending, then trailer
 * descending, so the newest version of a key sorts first.
 *
 * <p><b>Threading:</b> value type; {@code equals}/{@code hashCode} are by value (the
 * {@code byte[]} component forces an explicit override).
 *
 * @see <a href="https://github.com/google/leveldb/blob/main/db/dbformat.h">LevelDB dbformat.h</a>
 */
@Immutable
public record InternalKey(byte[] userKey, long sequenceNumber, ValueType valueType) {

  /** 56 bits of sequence space; a larger value is a caller bug. */
  public static final long MAX_SEQUENCE = (1L << 56) - 1;

  private static final int TRAILER_BYTES = 8;

  public InternalKey {
    if (userKey == null) throw new IllegalArgumentException("userKey is null");
    if (valueType == null) throw new IllegalArgumentException("valueType is null");
    if (sequenceNumber < 0 || sequenceNumber > MAX_SEQUENCE) {
      throw new IllegalArgumentException(
          "sequenceNumber out of 56-bit range: " + sequenceNumber);
    }
  }

  /** Packs {@code (sequence << 8) | typeCode} into the fixed64 trailer value. */
  public static long packTrailer(long sequenceNumber, ValueType valueType) {
    return (sequenceNumber << 8) | (valueType.code() & 0xFF);
  }

  public int encodedLength() { return userKey.length + TRAILER_BYTES; }

  public byte[] encode() {
    byte[] out = new byte[encodedLength()];
    System.arraycopy(userKey, 0, out, 0, userKey.length);
    LittleEndian.putFixed64(out, userKey.length, packTrailer(sequenceNumber, valueType));
    return out;
  }

  /** Decodes an encoded internal key; a length below the 8-byte trailer is corruption. */
  public static InternalKey decode(byte[] encoded) {
    if (encoded.length < TRAILER_BYTES) {
      throw new CorruptionException(
          "internal key shorter than trailer", 0, TRAILER_BYTES, encoded.length);
    }
    int userLen = encoded.length - TRAILER_BYTES;
    long trailer = LittleEndian.getFixed64(encoded, userLen);
    ValueType type = ValueType.fromCode((byte) (trailer & 0xFF));
    return new InternalKey(Arrays.copyOf(encoded, userLen), trailer >>> 8, type);
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof InternalKey k
        && sequenceNumber == k.sequenceNumber
        && valueType == k.valueType
        && Arrays.equals(userKey, k.userKey);
  }

  @Override
  public int hashCode() {
    return (Arrays.hashCode(userKey) * 31 + Long.hashCode(sequenceNumber)) * 31
        + valueType.hashCode();
  }

  @Override
  public String toString() {
    return "InternalKey[len=" + userKey.length + ", seq=" + sequenceNumber
        + ", type=" + valueType + "]";
  }
}
```

```java
// package-info.java
/**
 * The internal-key encoding (ADR-0004): user key + packed (sequence, value type), and the
 * comparator that orders it newest-first. Referenced by the memtable, every SSTable, the
 * WAL, compaction, and MVCC — the single most load-bearing format in the engine.
 *
 * <p><b>Threading:</b> all types here are immutable value types / stateless comparators.
 * <b>Entry point:</b> {@link InternalKey}.
 */
package dev.shale.internal.key;
```

- [ ] **Step 5: Run — expect pass.** Same command → PASS.

- [ ] **Step 6: Commit**

```bash
git add shale-core/src/main/java/dev/shale/internal/key/InternalKey.java \
        shale-core/src/main/java/dev/shale/internal/key/package-info.java \
        shale-core/src/test/java/dev/shale/internal/key/InternalKeyTest.java \
        shale-core/src/test/java/dev/shale/internal/key/InternalKeyPropertyTest.java
git commit -m "feat(key): add InternalKey encode/decode with packed trailer

Milestone: M0
Reversible: no — the internal-key byte encoding (ADR-0004)
Refs: documentation/conventions/on-disk-formats.md §5; ADR-0004"
```

---

## Task 8: `ByteRange`, `KeyComparator`, `BytewiseComparator`

**Files:**
- Create: `shale-core/src/main/java/dev/shale/{ByteRange,KeyComparator,BytewiseComparator}.java`
- Test: `shale-core/src/test/java/dev/shale/BytewiseComparatorTest.java`
- Test: `shale-core/src/test/java/dev/shale/BytewiseComparatorPropertyTest.java`

`BytewiseComparator` is **unsigned** lexicographic (byte `0xFF` sorts after `0x01`). The
property test asserts it is a total order (`testing.md` §1 Property), since every later
structure relies on that. The comparator operates on `ByteRange` views — an
`(array, offset, length)` value type — so it can compare sub-ranges (a user-key prefix of
an encoded internal key) without copying, while keeping the method at two parameters
(`ParameterNumber` ≤ 4). The unit/property tests below drive the `byte[]` convenience
overload, which delegates to the `ByteRange` form.

- [ ] **Step 1: Write the failing unit test**

```java
// BytewiseComparatorTest.java
package dev.shale;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class BytewiseComparatorTest {

  private final KeyComparator cmp = BytewiseComparator.INSTANCE;

  @Test
  void compares_unsigned() {
    // 0x80 (128) must sort AFTER 0x7F (127), not before (signed byte trap)
    assertThat(cmp.compare(new byte[] {(byte) 0x80}, new byte[] {0x7F})).isPositive();
  }

  @Test
  void shorterPrefix_sortsFirst() {
    assertThat(cmp.compare(new byte[] {1, 2}, new byte[] {1, 2, 3})).isNegative();
  }

  @Test
  void equalArrays_compareEqual() {
    assertThat(cmp.compare(new byte[] {1, 2, 3}, new byte[] {1, 2, 3})).isZero();
  }

  @Test
  void name_isStable() {
    assertThat(cmp.name()).isEqualTo("shale.BytewiseComparator");
  }
}
```

- [ ] **Step 2: Write the failing property test**

```java
// BytewiseComparatorPropertyTest.java
package dev.shale;

import static org.assertj.core.api.Assertions.assertThat;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;

class BytewiseComparatorPropertyTest {

  private final KeyComparator cmp = BytewiseComparator.INSTANCE;

  @Property
  void antisymmetric(@ForAll byte[] a, @ForAll byte[] b) {
    assertThat(Integer.signum(cmp.compare(a, b)))
        .isEqualTo(-Integer.signum(cmp.compare(b, a)));
  }

  @Property
  void transitive(@ForAll byte[] a, @ForAll byte[] b, @ForAll byte[] c) {
    if (cmp.compare(a, b) <= 0 && cmp.compare(b, c) <= 0) {
      assertThat(cmp.compare(a, c)).isLessThanOrEqualTo(0);
    }
  }

  @Property
  void consistentWithEquality(@ForAll byte[] a) {
    assertThat(cmp.compare(a, a.clone())).isZero();
  }
}
```

- [ ] **Step 3: Run — expect fail.** Command: `./gradlew :shale-core:test --tests 'dev.shale.BytewiseComparator*' --console=plain` → FAIL.

- [ ] **Step 4: Implement `ByteRange`**

```java
// ByteRange.java
package dev.shale;

import dev.shale.internal.annotations.Immutable;

/**
 * A view over {@code array[offset, offset+length)} — the unit a {@link KeyComparator}
 * orders. Lets comparators inspect a sub-range (a user-key prefix of an encoded internal
 * key) without copying, while keeping method arity low.
 *
 * <p><b>Threading:</b> immutable value; the referenced array is caller-owned and must not
 * be mutated while the range is in use.
 */
@Immutable
public record ByteRange(byte[] array, int offset, int length) {

  public ByteRange {
    if (array == null) {
      throw new IllegalArgumentException("array is null");
    }
    if (offset < 0 || length < 0 || offset + length > array.length) {
      throw new IllegalArgumentException(
          "range [" + offset + "," + (offset + length) + ") outside array of "
              + array.length);
    }
  }

  /** A range spanning the whole array. */
  public static ByteRange of(byte[] array) {
    return new ByteRange(array, 0, array.length);
  }
}
```

- [ ] **Step 5: Implement `KeyComparator`**

```java
// KeyComparator.java
package dev.shale;

/**
 * Orders user keys. Pluggable and named: the {@link #name()} is persisted in the manifest
 * (from M5) so a database cannot be reopened with an incompatible ordering.
 *
 * <p><b>Threading:</b> implementations must be thread-safe and stateless.
 */
public interface KeyComparator {

  /** Total order over the two ranges; negative / zero / positive like {@code compareTo}. */
  int compare(ByteRange a, ByteRange b);

  /** Stable identity persisted with the data; changing it is a breaking change. */
  String name();

  /** Convenience overload comparing whole arrays. */
  default int compare(byte[] a, byte[] b) {
    return compare(ByteRange.of(a), ByteRange.of(b));
  }
}
```

- [ ] **Step 6: Implement `BytewiseComparator`**

```java
// BytewiseComparator.java
package dev.shale;

import dev.shale.internal.annotations.ThreadSafe;

/**
 * Unsigned lexicographic ordering — LevelDB's default. Byte {@code 0xFF} sorts after
 * {@code 0x01}; a shorter key that is a prefix of a longer one sorts first.
 *
 * <p><b>Threading:</b> stateless singleton.
 *
 * @see <a href="https://github.com/google/leveldb/blob/main/util/comparator.cc">LevelDB comparator.cc</a>
 */
@ThreadSafe
public final class BytewiseComparator implements KeyComparator {

  public static final BytewiseComparator INSTANCE = new BytewiseComparator();

  private BytewiseComparator() {}

  @Override
  public int compare(ByteRange a, ByteRange b) {
    int min = Math.min(a.length(), b.length());
    byte[] aa = a.array();
    byte[] bb = b.array();
    for (int i = 0; i < min; i++) {
      int x = aa[a.offset() + i] & 0xFF;
      int y = bb[b.offset() + i] & 0xFF;
      if (x != y) {
        return x - y;
      }
    }
    return a.length() - b.length();
  }

  @Override
  public String name() {
    return "shale.BytewiseComparator";
  }
}
```

- [ ] **Step 7: Run — expect pass.** Same command → PASS. (Add a small `ByteRangeTest`
  asserting `of()` spans the whole array and the compact constructor rejects an
  out-of-bounds `(offset, length)` with `IllegalArgumentException`.)

- [ ] **Step 8: Commit**

```bash
git add shale-core/src/main/java/dev/shale/ByteRange.java \
        shale-core/src/main/java/dev/shale/KeyComparator.java \
        shale-core/src/main/java/dev/shale/BytewiseComparator.java \
        shale-core/src/test/java/dev/shale/ByteRangeTest.java \
        shale-core/src/test/java/dev/shale/BytewiseComparator*.java
git commit -m "feat(api): add ByteRange, KeyComparator SPI, and BytewiseComparator

Comparator orders ByteRange views so it can compare sub-ranges without copying.

Milestone: M0
Reversible: no — comparator identity is persisted (ADR-0006)
Refs: documentation/conventions/on-disk-formats.md §5; ADR-0006"
```

---

## Task 9: `InternalKeyComparator`

**Files:**
- Create: `shale-core/src/main/java/dev/shale/internal/key/InternalKeyComparator.java`
- Test: `shale-core/src/test/java/dev/shale/internal/key/InternalKeyComparatorTest.java`

Orders *encoded* internal keys: user key ascending by the user comparator, then trailer
**descending** so the newest sequence sorts first. This is the single ordering rule that,
if reversed, makes every read return stale data (ADR-0004).

- [ ] **Step 1: Write the failing test**

```java
// InternalKeyComparatorTest.java
package dev.shale.internal.key;

import static org.assertj.core.api.Assertions.assertThat;
import dev.shale.BytewiseComparator;
import org.junit.jupiter.api.Test;

class InternalKeyComparatorTest {

  private final InternalKeyComparator cmp =
      new InternalKeyComparator(BytewiseComparator.INSTANCE);

  @Test
  void differentUserKeys_orderByUserKeyAscending() {
    byte[] a = new InternalKey(new byte[] {1}, 100, ValueType.PUT).encode();
    byte[] b = new InternalKey(new byte[] {2}, 1, ValueType.PUT).encode();
    assertThat(cmp.compare(a, b)).isNegative();
  }

  @Test
  void sameUserKey_higherSequenceSortsFirst() {
    byte[] newer = new InternalKey(new byte[] {1}, 100, ValueType.PUT).encode();
    byte[] older = new InternalKey(new byte[] {1}, 50, ValueType.PUT).encode();
    assertThat(cmp.compare(newer, older)).isNegative(); // newer sorts before older
  }

  @Test
  void name_wrapsUserComparatorName() {
    assertThat(cmp.name()).contains("shale.BytewiseComparator");
  }
}
```

- [ ] **Step 2: Run — expect fail.** Command: `./gradlew :shale-core:test --tests 'dev.shale.internal.key.InternalKeyComparatorTest' --console=plain` → FAIL.

- [ ] **Step 3: Implement**

```java
// InternalKeyComparator.java
package dev.shale.internal.key;

import dev.shale.ByteRange;
import dev.shale.KeyComparator;
import dev.shale.internal.annotations.ThreadSafe;
import dev.shale.internal.coding.LittleEndian;

/**
 * Orders encoded internal keys: user key ascending by the wrapped {@link KeyComparator},
 * then the 8-byte trailer descending so the newest sequence (largest trailer) sorts
 * first and a seek lands directly on it (ADR-0004).
 *
 * <p><b>Threading:</b> stateless; safe for concurrent use if the wrapped comparator is.
 */
@ThreadSafe
public final class InternalKeyComparator implements KeyComparator {

  private static final int TRAILER_BYTES = 8;

  private final KeyComparator userComparator;

  public InternalKeyComparator(KeyComparator userComparator) {
    this.userComparator = userComparator;
  }

  @Override
  public int compare(ByteRange a, ByteRange b) {
    if (a.length() < TRAILER_BYTES || b.length() < TRAILER_BYTES) {
      throw new IllegalArgumentException("encoded internal key shorter than trailer");
    }
    int aUserLen = a.length() - TRAILER_BYTES;
    int bUserLen = b.length() - TRAILER_BYTES;
    ByteRange aUser = new ByteRange(a.array(), a.offset(), aUserLen);
    ByteRange bUser = new ByteRange(b.array(), b.offset(), bUserLen);
    int c = userComparator.compare(aUser, bUser);
    if (c != 0) {
      return c;
    }
    long aTrailer = LittleEndian.getFixed64(a.array(), a.offset() + aUserLen);
    long bTrailer = LittleEndian.getFixed64(b.array(), b.offset() + bUserLen);
    // Descending: larger trailer (newer) is "less" so it sorts first.
    return Long.compareUnsigned(bTrailer, aTrailer);
  }

  @Override
  public String name() {
    return "shale.InternalKeyComparator(" + userComparator.name() + ")";
  }
}
```

- [ ] **Step 4: Run — expect pass.** Same command → PASS.

- [ ] **Step 5: Commit**

```bash
git add shale-core/src/main/java/dev/shale/internal/key/InternalKeyComparator.java \
        shale-core/src/test/java/dev/shale/internal/key/InternalKeyComparatorTest.java
git commit -m "feat(key): add InternalKeyComparator (userkey asc, seq desc)

Milestone: M0
Reversible: no — ordering is part of the internal-key contract (ADR-0004)
Refs: documentation/conventions/on-disk-formats.md §5; ADR-0004"
```

---

## Task 10: `Durability`, `Cursor`, `StorageBackend` (ADR-0006)

**Files:**
- Create: `shale-core/src/main/java/dev/shale/{Durability,Cursor,StorageBackend}.java`
- Create: `shale-core/src/main/java/dev/shale/package-info.java`

No test in this task — these are pure interfaces/enum with no behaviour; Task 12–14 exercise
them through the reference backend. (A test asserting an interface compiles is noise.)

- [ ] **Step 1: Write `Durability`**

```java
// Durability.java
package dev.shale;

import dev.shale.internal.annotations.Immutable;

/**
 * The durability guarantee a write demands before it is acknowledged (N3,
 * concurrency-and-resources.md §5). No default: the caller always chooses.
 *
 * <p>At M0 the only {@link StorageBackend} is in-memory and non-durable, so it validates
 * the argument as non-null but cannot honour it; these modes gain meaning with the WAL
 * (M1). Never widen or silently narrow a guarantee (D4).
 */
@Immutable
public enum Durability {
  /** Buffered only; survives process crash, not power loss. */
  NONE,
  /** fsync'd before returning; survives power loss. */
  SYNC,
  /** Batched with concurrent writers into a shared fsync (group commit). */
  GROUP
}
```

- [ ] **Step 2: Write `Cursor`**

```java
// Cursor.java
package dev.shale;

import dev.shale.internal.annotations.NotThreadSafe;

/**
 * A forward cursor over an ordered range of user keys. Positioned on construction at the
 * first key ≥ the scan's lower bound; {@link #isValid()} is false once exhausted.
 *
 * <p><b>Threading:</b> single-threaded; owned by the thread that opened it. The caller
 * must {@link #close()} it.
 */
@NotThreadSafe
public interface Cursor extends AutoCloseable {

  /** True while positioned on a live entry; false once the range is exhausted. */
  boolean isValid();

  /** Advances to the next user key in ascending order. */
  void next();

  /** The current user key. Callers must not mutate the returned array. */
  byte[] key();

  /** The current value. Callers must not mutate the returned array. */
  byte[] value();

  @Override
  void close();
}
```

- [ ] **Step 3: Write `StorageBackend`**

```java
// StorageBackend.java
package dev.shale;

/**
 * The single-node storage SPI: a durable, ordered {@code byte[]}→{@code byte[]} map. This
 * is the seam behind which the LSM engine and, at M8, a copy-on-write B+Tree both sit, so
 * the same benchmark harness can compare them (ADR-0006, roadmap §D).
 *
 * <p>Keys and values are opaque bytes; ordering is defined entirely by {@link
 * #comparator()}. Every acknowledging write takes an explicit {@link Durability} (N3).
 *
 * <p><b>Threading:</b> implementations state their own contract. <b>Ownership:</b> the
 * caller owns the instance and must {@link #close()} it.
 */
public interface StorageBackend extends AutoCloseable {

  /** Associates {@code value} with {@code userKey}, overwriting any previous value. */
  void put(byte[] userKey, byte[] value, Durability durability);

  /** Removes {@code userKey} if present; a later {@link #get} returns {@code null}. */
  void delete(byte[] userKey, Durability durability);

  /** The value for {@code userKey}, or {@code null} if absent. The one documented
   *  {@code null}-return path (java-style.md §6). */
  byte[] get(byte[] userKey);

  /**
   * An ordered cursor over user keys in {@code [fromInclusive, toExclusive)}. A {@code
   * null} bound is open-ended on that side.
   */
  Cursor scan(byte[] fromInclusive, byte[] toExclusive);

  /** The ordering this backend was opened with; its name gates reopen compatibility. */
  KeyComparator comparator();

  @Override
  void close();
}
```

- [ ] **Step 4: Write `package-info.java`**

```java
// package-info.java
/**
 * The public API of {@code shale-core}: the {@link dev.shale.StorageBackend} SPI, the
 * {@link dev.shale.KeyComparator} it orders by, {@link dev.shale.Durability}, and the
 * {@link dev.shale.ShaleException} hierarchy. Everything not under {@code dev.shale.internal}
 * is public API — signature changes here require an ADR (naming.md §3).
 *
 * <p><b>Threading:</b> per type. <b>Entry point:</b> {@link dev.shale.StorageBackend}.
 */
package dev.shale;
```

- [ ] **Step 5: Compile.** Run: `./gradlew :shale-core:compileJava --console=plain` → `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
git add shale-core/src/main/java/dev/shale/Durability.java \
        shale-core/src/main/java/dev/shale/Cursor.java \
        shale-core/src/main/java/dev/shale/StorageBackend.java \
        shale-core/src/main/java/dev/shale/package-info.java
git commit -m "feat(api): define StorageBackend SPI, Cursor, and Durability

Milestone: M0
Reversible: no — the SPI every backend implements (ADR-0006)
Refs: roadmap §D; documentation/adr/0006-storage-backend-spi.md; ADR-0006"
```

---

## Task 11: `ReferenceModel` oracle (test scope)

**Files:** Create `shale-core/src/test/java/dev/shale/model/ReferenceModel.java`

The oracle: a `TreeMap<byte[],byte[]>` keyed by the same unsigned ordering, representing
the *observable* sorted-map behaviour the engine must match. No MVCC — deletes remove,
overwrites replace. Keys/values are defensively copied.

- [ ] **Step 1: Write it** (no separate test — it is exercised by Task 13/14)

```java
// ReferenceModel.java
package dev.shale.model;

import dev.shale.BytewiseComparator;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/** In-memory reference: the sorted-map semantics the engine must reproduce (testing.md §1). */
final class ReferenceModel {

  private final NavigableMap<byte[], byte[]> map =
      new TreeMap<>(BytewiseComparator.INSTANCE::compare);

  void put(byte[] userKey, byte[] value) {
    map.put(userKey.clone(), value.clone());
  }

  void delete(byte[] userKey) {
    map.remove(userKey);
  }

  byte[] get(byte[] userKey) {
    byte[] v = map.get(userKey);
    return v == null ? null : v.clone();
  }

  /** Entries in {@code [from, to)} (null bound = open-ended), ascending, as [key, value] pairs. */
  List<byte[][]> entries(byte[] fromInclusive, byte[] toExclusive) {
    NavigableMap<byte[], byte[]> view = map;
    if (fromInclusive != null) view = view.tailMap(fromInclusive, true);
    if (toExclusive != null) view = view.headMap(toExclusive, false);
    List<byte[][]> out = new ArrayList<>();
    for (Map.Entry<byte[], byte[]> e : view.entrySet()) {
      out.add(new byte[][] {e.getKey().clone(), e.getValue().clone()});
    }
    return out;
  }
}
```

- [ ] **Step 2: Compile tests.** Run: `./gradlew :shale-core:compileTestJava --console=plain` → `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add shale-core/src/test/java/dev/shale/model/ReferenceModel.java
git commit -m "test(api): add TreeMap reference model oracle

Milestone: M0
Refs: documentation/conventions/testing.md §1"
```

---

## Task 12: `ReferenceBackend` (test scope) — the M0 artifact

**Files:** Create `shale-core/src/test/java/dev/shale/model/ReferenceBackend.java`

An in-memory `StorageBackend` keyed by **encoded internal keys** via
`InternalKeyComparator`, assigning increasing sequence numbers. It is deliberately built
on the real M0 encoding/ordering code so the harness exercises `InternalKey`, `ValueType`,
sequence numbering, and MVCC-style lookup — not a trivial map. It is a test oracle, **not**
the engine (the engine's memtable is M2, N1).

- [ ] **Step 1: Write it**

```java
// ReferenceBackend.java
package dev.shale.model;

import dev.shale.ByteRange;
import dev.shale.BytewiseComparator;
import dev.shale.Cursor;
import dev.shale.Durability;
import dev.shale.KeyComparator;
import dev.shale.StorageBackend;
import dev.shale.internal.key.InternalKey;
import dev.shale.internal.key.InternalKeyComparator;
import dev.shale.internal.key.ValueType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * In-memory {@link StorageBackend} over encoded internal keys, for driving the model
 * harness. Exercises the real M0 encoding and newest-first ordering. Non-durable: the
 * {@link Durability} argument is validated but cannot be honoured until the WAL (M1).
 *
 * <p><b>Threading:</b> single-threaded; test-owned.
 */
final class ReferenceBackend implements StorageBackend {

  private final KeyComparator userComparator = BytewiseComparator.INSTANCE;
  private final NavigableMap<byte[], byte[]> store;
  private long nextSequence = 1;

  ReferenceBackend() {
    InternalKeyComparator ikc = new InternalKeyComparator(userComparator);
    this.store = new TreeMap<>((x, y) -> ikc.compare(ByteRange.of(x), ByteRange.of(y)));
  }

  @Override
  public void put(byte[] userKey, byte[] value, Durability durability) {
    require(userKey, value, durability);
    byte[] ik = new InternalKey(userKey, nextSequence++, ValueType.PUT).encode();
    store.put(ik, value.clone());
  }

  @Override
  public void delete(byte[] userKey, Durability durability) {
    if (userKey == null) throw new IllegalArgumentException("userKey is null");
    if (durability == null) throw new IllegalArgumentException("durability is null");
    byte[] ik = new InternalKey(userKey, nextSequence++, ValueType.DELETE).encode();
    store.put(ik, new byte[0]);
  }

  @Override
  public byte[] get(byte[] userKey) {
    if (userKey == null) throw new IllegalArgumentException("userKey is null");
    byte[] lookup =
        new InternalKey(userKey, InternalKey.MAX_SEQUENCE, ValueType.FOR_SEEK).encode();
    Map.Entry<byte[], byte[]> e = store.ceilingEntry(lookup);
    if (e == null) return null;
    InternalKey found = InternalKey.decode(e.getKey());
    if (userComparator.compare(found.userKey(), userKey) != 0) return null; // no such key
    return found.valueType() == ValueType.PUT ? e.getValue().clone() : null; // else tombstone
  }

  @Override
  public Cursor scan(byte[] fromInclusive, byte[] toExclusive) {
    List<byte[]> keys = new ArrayList<>();
    List<byte[]> values = new ArrayList<>();
    byte[] previousUserKey = null;
    for (Map.Entry<byte[], byte[]> e : store.entrySet()) {
      InternalKey ik = InternalKey.decode(e.getKey());
      byte[] userKey = ik.userKey();
      if (previousUserKey != null
          && userComparator.compare(userKey, previousUserKey) == 0) {
        continue; // older version of a key we already resolved
      }
      previousUserKey = userKey;
      if (fromInclusive != null && userComparator.compare(userKey, fromInclusive) < 0) continue;
      if (toExclusive != null && userComparator.compare(userKey, toExclusive) >= 0) continue;
      if (ik.valueType() == ValueType.DELETE) continue; // tombstone hides the key
      keys.add(userKey.clone());
      values.add(e.getValue().clone());
    }
    return new ListCursor(keys, values);
  }

  @Override
  public KeyComparator comparator() {
    return userComparator;
  }

  @Override
  public void close() {
    store.clear();
  }

  private static void require(byte[] userKey, byte[] value, Durability durability) {
    if (userKey == null) throw new IllegalArgumentException("userKey is null");
    if (value == null) throw new IllegalArgumentException("value is null");
    if (durability == null) throw new IllegalArgumentException("durability is null");
  }

  /** Forward cursor over materialised lists. */
  private static final class ListCursor implements Cursor {
    private final List<byte[]> keys;
    private final List<byte[]> values;
    private int index;

    ListCursor(List<byte[]> keys, List<byte[]> values) {
      this.keys = keys;
      this.values = values;
    }

    @Override public boolean isValid() { return index < keys.size(); }
    @Override public void next() { index++; }
    @Override public byte[] key() { return keys.get(index); }
    @Override public byte[] value() { return values.get(index); }
    @Override public void close() { /* nothing to release */ }
  }
}
```

- [ ] **Step 2: Compile tests.** Run: `./gradlew :shale-core:compileTestJava --console=plain` → `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add shale-core/src/test/java/dev/shale/model/ReferenceBackend.java
git commit -m "test(api): add in-memory ReferenceBackend over internal keys

Drives the model harness through the real M0 encoding and newest-first
ordering; not the engine (memtable is M2).

Milestone: M0
Refs: documentation/conventions/testing.md §1"
```

---

## Task 13: Harness helpers — `Seeds` and `Backends`

**Files:** Create
`shale-core/src/test/java/dev/shale/model/{Seeds,Backends}.java`

- [ ] **Step 1: Write `Seeds`** (seed sourcing; the reproduce hint is emitted by the
  caller in its failure message, since `System.out` is banned — testing.md §2, java-style.md §7)

```java
// Seeds.java
package dev.shale.model;

import java.security.SecureRandom;

/**
 * Sources the per-run test seed: {@code -Dshale.test.seed} if set, else a fresh random
 * seed. The value is echoed into the assertion-failure message by the caller (never on
 * stdout — System.out is banned), so every failure is reproducible (testing.md §2).
 */
final class Seeds {
  private Seeds() {}

  static long resolve() {
    String property = System.getProperty("shale.test.seed");
    return (property != null) ? Long.parseLong(property) : new SecureRandom().nextLong();
  }
}
```

- [ ] **Step 2: Write `Backends`** (the diff routine used by both the model test and the self-test)

```java
// Backends.java
package dev.shale.model;

import static org.assertj.core.api.Assertions.assertThat;

import dev.shale.Cursor;
import dev.shale.StorageBackend;
import java.util.ArrayList;
import java.util.List;

/** Comparison helpers: drain a {@link Cursor}, and assert a backend matches the model. */
final class Backends {
  private Backends() {}

  /** Materialises a cursor into ascending [key, value] pairs, then closes it. */
  static List<byte[][]> drain(Cursor cursor) {
    List<byte[][]> out = new ArrayList<>();
    try (cursor) {
      while (cursor.isValid()) {
        out.add(new byte[][] {cursor.key().clone(), cursor.value().clone()});
        cursor.next();
      }
    }
    return out;
  }

  /**
   * Asserts the backend agrees with the model on point lookups for every probe key and on
   * full ascending iteration. Throws {@link AssertionError} on the first divergence.
   */
  static void assertMatches(
      StorageBackend backend, ReferenceModel model, List<byte[]> probeKeys) {
    for (byte[] key : probeKeys) {
      assertThat(backend.get(key))
          .as("get(%s)", java.util.Arrays.toString(key))
          .isEqualTo(model.get(key));
    }
    List<byte[][]> actual = drain(backend.scan(null, null));
    List<byte[][]> expected = model.entries(null, null);
    assertThat(actual)
        .as("full scan order and contents")
        .usingElementComparator(Backends::comparePairs)
        .containsExactlyElementsOf(expected);
  }

  private static int comparePairs(byte[][] a, byte[][] b) {
    int c = java.util.Arrays.compare(a[0], b[0]);
    return c != 0 ? c : java.util.Arrays.compare(a[1], b[1]);
  }
}
```

- [ ] **Step 3: Compile tests.** Run: `./gradlew :shale-core:compileTestJava --console=plain` → `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add shale-core/src/test/java/dev/shale/model/Seeds.java \
        shale-core/src/test/java/dev/shale/model/Backends.java
git commit -m "test(api): add seed logging and backend/model diff helpers

Milestone: M0
Refs: documentation/conventions/testing.md §1,§2"
```

---

## Task 14: The model harness and its self-test

**Files:** Create
`shale-core/src/test/java/dev/shale/model/{BuggyBackend,HarnessSelfTest,StorageBackendModelTest}.java`

Two tests: `HarnessSelfTest` proves the diff routine *fails* on a wrong backend (a harness
that never fails is worthless); `StorageBackendModelTest` runs a seeded random op sequence
against `ReferenceBackend` and the oracle, asserting they agree after every operation.

- [ ] **Step 1: Write `BuggyBackend`** (a `ReferenceBackend` that ignores deletes)

```java
// BuggyBackend.java
package dev.shale.model;

import dev.shale.Durability;

/** A backend seeded with one bug — it ignores deletes — used to prove the harness bites. */
final class BuggyBackend {
  private BuggyBackend() {}

  /** Wraps a real backend but drops delete calls. */
  static dev.shale.StorageBackend ignoringDeletes(dev.shale.StorageBackend delegate) {
    return new dev.shale.StorageBackend() {
      @Override public void put(byte[] k, byte[] v, Durability d) { delegate.put(k, v, d); }
      @Override public void delete(byte[] k, Durability d) { /* BUG: ignored */ }
      @Override public byte[] get(byte[] k) { return delegate.get(k); }
      @Override public dev.shale.Cursor scan(byte[] from, byte[] to) { return delegate.scan(from, to); }
      @Override public dev.shale.KeyComparator comparator() { return delegate.comparator(); }
      @Override public void close() { delegate.close(); }
    };
  }
}
```

- [ ] **Step 2: Write the failing self-test**

```java
// HarnessSelfTest.java
package dev.shale.model;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.shale.Durability;
import dev.shale.StorageBackend;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The harness must detect a divergence; a diff routine that never fails proves nothing. */
class HarnessSelfTest {

  @Test
  void assertMatches_detectsBackendThatIgnoresDeletes() {
    StorageBackend buggy = BuggyBackend.ignoringDeletes(new ReferenceBackend());
    ReferenceModel model = new ReferenceModel();
    byte[] key = {1};

    buggy.put(key, new byte[] {9}, Durability.NONE);
    model.put(key, new byte[] {9});
    buggy.delete(key, Durability.NONE); // buggy keeps the value…
    model.delete(key); // …model removes it

    assertThatThrownBy(() -> Backends.assertMatches(buggy, model, List.of(key)))
        .isInstanceOf(AssertionError.class);
  }
}
```

- [ ] **Step 3: Run — expect fail** (`BuggyBackend`/`Backends` not yet compiling until Step 1/Task 13 present)

Run: `./gradlew :shale-core:test --tests 'dev.shale.model.HarnessSelfTest' --console=plain`
Expected: FAIL, then PASS once Step 1 compiles. (If it already passes, good.)

- [ ] **Step 4: Write the model harness**

```java
// StorageBackendModelTest.java
package dev.shale.model;

import dev.shale.Durability;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Runs a seeded random operation sequence against {@link ReferenceBackend} and the {@link
 * ReferenceModel} oracle, asserting agreement after every operation and on full iteration.
 * The highest-value test in the project (testing.md §1).
 *
 * <p>M0 exercises put / overwrite / delete / get / scan. restart, flush, compaction, and
 * snapshot reads are added to this same harness as those mechanisms arrive (M1–M7).
 */
@Tag("model")
class StorageBackendModelTest {

  private static final int OPERATIONS = 5_000;
  private static final int KEY_SPACE = 32; // small, to force overwrites and deletes

  @Test
  void randomOperations_matchTreeMapModel() {
    long seed = Seeds.resolve();
    try {
      run(seed);
    } catch (AssertionError failure) {
      throw new AssertionError(
          "model divergence — reproduce with -Dshale.test.seed=" + seed, failure);
    }
  }

  @Test
  void pinnedRegression_seed1() {
    run(1L); // pin a representative seed so a known-good sequence always runs
  }

  private void run(long seed) {
    Random random = new Random(seed);
    ReferenceBackend backend = new ReferenceBackend();
    ReferenceModel model = new ReferenceModel();
    List<byte[]> probeKeys = allKeys();

    for (int op = 0; op < OPERATIONS; op++) {
      byte[] key = key(random.nextInt(KEY_SPACE));
      int roll = random.nextInt(100);
      if (roll < 65) { // put / overwrite
        byte[] value = value(random);
        backend.put(key, value, Durability.NONE);
        model.put(key, value);
      } else if (roll < 85) { // delete
        backend.delete(key, Durability.NONE);
        model.delete(key);
      } else { // get (read-only sanity between mutations)
        // asserted below against the model
      }
      if (op % 100 == 0) {
        Backends.assertMatches(backend, model, probeKeys);
      }
    }
    Backends.assertMatches(backend, model, probeKeys); // final full check
  }

  private static List<byte[]> allKeys() {
    List<byte[]> keys = new ArrayList<>(KEY_SPACE);
    for (int i = 0; i < KEY_SPACE; i++) keys.add(key(i));
    return keys;
  }

  private static byte[] key(int i) {
    return new byte[] {(byte) (i >>> 8), (byte) i};
  }

  private static byte[] value(Random random) {
    byte[] value = new byte[1 + random.nextInt(8)];
    random.nextBytes(value);
    return value;
  }
}
```

- [ ] **Step 5: Run — expect pass**

Run: `./gradlew :shale-core:test --tests 'dev.shale.model.*' --console=plain`
Expected: PASS (both tests). On a failure, the thrown `AssertionError` message carries the
seed and the `-Dshale.test.seed=…` reproduce hint.

- [ ] **Step 6: Make the seed property reach the forked test JVM, then verify reproducibility.**
  Gradle does not forward CLI `-D` properties to the test JVM by default. Add this to the
  root `build.gradle.kts` `subprojects { }` block (a one-line `build` change, its own
  commit) so `shale.*` properties pass through:

```kotlin
tasks.withType<Test>().configureEach {
  System.getProperties().forEach { (k, v) ->
    if (k.toString().startsWith("shale.")) systemProperty(k.toString(), v.toString())
  }
}
```

Then run with a fixed seed twice and confirm identical results:

Run: `./gradlew :shale-core:test --tests 'dev.shale.model.StorageBackendModelTest' -Dshale.test.seed=12345 --console=plain`
Expected: PASS, deterministic. (The `pinnedRegression_seed1` test already guarantees one
fixed sequence runs every build regardless of this forwarding.)

- [ ] **Step 7: Commit**

```bash
git add shale-core/src/test/java/dev/shale/model/BuggyBackend.java \
        shale-core/src/test/java/dev/shale/model/HarnessSelfTest.java \
        shale-core/src/test/java/dev/shale/model/StorageBackendModelTest.java
git commit -m "test(api): add seeded model harness and its self-test

Diffs ReferenceBackend against a TreeMap oracle after every op; the self-test
proves the diff routine fails on a backend that ignores deletes.

Milestone: M0
Refs: documentation/conventions/testing.md §1,§2"
```

---

## Task 15: Full build gate and milestone release note

**Files:** Create `documentation/roadmap/m0-release-note.md`; Modify none else.

- [ ] **Step 1: Run the full gate**

Run: `./gradlew build --console=plain`
Expected: `BUILD SUCCESSFUL`; checkstyle and spotless clean on the new sources; the model
and property tests run inside `test` (they are not tagged `crash`/`soak`).

- [ ] **Step 2: Confirm formatting and style specifically**

Run: `./gradlew :shale-core:spotlessCheck :shale-core:checkstyleMain :shale-core:checkstyleTest --console=plain`
Expected: `BUILD SUCCESSFUL`. If spotless fails, run `./gradlew spotlessApply` and commit
the formatting in a separate `style` commit (never mix with behaviour, commits.md §3).

- [ ] **Step 3: Write the release note** — a short note per `commits.md` §5 (milestone tags):

```markdown
# M0 — Skeleton & Interfaces (release note)

Delivered: the StorageBackend SPI, KeyComparator + BytewiseComparator, the internal-key
encoding (InternalKey/ValueType/InternalKeyComparator, ADR-0004/0005), the exception
hierarchy, concurrency annotations, and the seeded TreeMap model harness with a self-test.

No storage mechanism yet (WAL is M1). The model harness is the durable asset: every later
milestone plugs its engine into it. Exit criteria (roadmap §E, testing.md §1) met:
`./gradlew build` green; model harness green and reproducible from its seed; harness
self-test proves it detects divergence.
```

- [ ] **Step 4: Commit and tag**

```bash
git add documentation/roadmap/m0-release-note.md
git commit -m "docs(docs): add M0 release note

Milestone: M0"
# After merge to main:
# git tag m0-skeleton && git push origin m0-skeleton
```

---

## Self-review (run against the roadmap M0 spec)

**Spec coverage** — roadmap §E M0 asks for: `byte[] get/put/delete` (Task 10, exercised
Task 12/14 ✓), a `StorageBackend` interface (Task 10 ✓), a comparator (Task 8 ✓),
internal-key encoding (Tasks 5–7, 9 ✓), and a `TreeMap` reference harness (Tasks 11–14 ✓).
Recommendations Stage 1 also asks the mission/goals live in a README — already satisfied by
the top-level `README.md` written during setup; no task needed. testing.md's demand that
the model harness be built in M0 "before the engine exists" is met (Task 14), with restart/
flush/compaction/snapshot explicitly deferred to the milestones that introduce them.

**Placeholder scan** — no `TBD`/"add error handling"/"write tests for the above"; every code
step shows complete code and every run step gives an exact command and expected result.

**Type consistency** — `StorageBackend`, `Cursor`, `Durability`, `ByteRange.of`,
`KeyComparator.compare(ByteRange,ByteRange)`, `BytewiseComparator.INSTANCE`,
`ValueType.{DELETE,PUT,FOR_SEEK,code,fromCode}`,
`InternalKey.{encode,decode,MAX_SEQUENCE,packTrailer}`, `InternalKeyComparator(userComparator)`,
`LittleEndian.{putFixed64,getFixed64}`, `CorruptionException(offset,expected,actual)`,
`Backends.{drain,assertMatches}`, `ReferenceModel.{put,delete,get,entries}`,
`ReferenceBackend`, `Seeds.resolve` — names are used identically across all tasks. The
comparator takes `ByteRange` (not `byte[],off,len`) everywhere, so `ParameterNumber` ≤ 4
holds and `InternalKeyComparator` reuses the user comparator on a sub-range without copy.

**Ordering caveat verified** — `InternalKeyComparator` returns `Long.compareUnsigned(bTrailer,
aTrailer)` (descending) so newest sorts first; `ReferenceBackend.get` uses `ceilingEntry`
of a `FOR_SEEK`/`MAX_SEQUENCE` lookup key, which lands on that newest version. These two must
stay consistent — a change to one without the other reintroduces the stale-read bug ADR-0004
warns about.

---

## Milestone exit criteria (Definition of Done)

1. `./gradlew build` is green; `shale-core` still has zero runtime dependencies (N1).
2. All property tests pass, including comparator total-order and encode/decode round-trips.
3. The model harness passes and is reproducible from `-Dshale.test.seed`.
4. The harness self-test proves the diff routine detects a wrong backend.
5. ADRs 0004/0005/0006 are `Accepted`; the ADR index matches the files on disk.
6. Every new type carries its concurrency annotation, a citation where it implements a known
   technique (N9), and every new package a `package-info.java`.

---

## Execution handoff

Plan complete. Two execution options:

1. **Subagent-Driven (recommended)** — dispatch a fresh subagent per task, review between
   tasks. Fast iteration, clean context per task.
2. **Inline Execution** — execute tasks in this session with checkpoints for review.

All work happens on an `m00/skeleton-and-interfaces` branch cut from `main`; ADRs land
first (Task 2), then bottom-up implementation, each task its own commit.
