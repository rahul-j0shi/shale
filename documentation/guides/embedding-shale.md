# Using the Shale storage engine in your application

Shale is an **embeddable storage engine**: a Java library that stores an ordered map of byte
keys to byte values in a directory on disk, durably. It is what LevelDB and RocksDB are, written
from scratch. You link it into your program; there is no server to run.

This guide is kept true to the code as it stands: each milestone updates it in the same change.
It describes the engine **as built through M4**. Where a limitation will lift at a later milestone,
it says which.

> **Is it production-ready?** No. Shale is a learning and portfolio project, with serious testing
> but no production use. Use it to learn, to experiment, or to read. Do not store data you cannot
> afford to lose.

---

## 1. Requirements

| | |
|---|---|
| **JDK** | 25 or newer. The repository can fetch a checksum-verified one for you (§2). |
| **OS** | Linux or macOS. Windows is not supported: durable directory syncs rely on POSIX behaviour. |
| **Dependencies** | None. `shale-core` has zero runtime dependencies, and the build fails if one appears. |

## 2. Try it in 30 seconds (nothing to set up but a JDK)

```bash
git clone https://github.com/rahul-j0shi/shale.git && cd shale
./scripts/bootstrap.sh && source scripts/env.sh      # JDK 25 into .tools/, if you don't have one
./gradlew :shale-core:jar
jshell --class-path shale-core/build/libs/shale-core-0.0.1-SNAPSHOT.jar scripts/try-shale.jsh
```

[`scripts/try-shale.jsh`](../../scripts/try-shale.jsh) opens a database in a temporary
directory, then writes, deletes, reads, scans, closes and reopens it, printing what it sees. The
`jshell>` prompt stays open with `db` in scope, so you can keep experimenting.

## 3. Add it to your project

There is no public Maven release yet. Install the engine into your local Maven repository:

```bash
./gradlew :shale-core:publishToMavenLocal     # installs dev.shale:shale-core:0.0.1-SNAPSHOT into ~/.m2
```

**Gradle** (Kotlin DSL):

```kotlin
repositories { mavenLocal(); mavenCentral() }
dependencies { implementation("dev.shale:shale-core:0.0.1-SNAPSHOT") }
java { toolchain { languageVersion.set(JavaLanguageVersion.of(25)) } }
```

**Maven:**

```xml
<dependency>
  <groupId>dev.shale</groupId>
  <artifactId>shale-core</artifactId>
  <version>0.0.1-SNAPSHOT</version>
</dependency>
```

Any JVM language (Kotlin, Scala, Clojure) can use it the same way. **From other languages**, use
ShaleDB instead: the database built on Shale speaks the PostgreSQL protocol (planned, milestone
D5).

## 4. The API in one page

```java
import dev.shale.*;

Path dir = Path.of("data");                                    // Shale owns this directory
try (Shale db = Shale.open(dir, Clock.system(), Metrics.NOOP)) {
  db.put(key, value, Durability.SYNC);                         // write
  byte[] v = db.get(key);                                      // null if absent or deleted
  db.delete(key, Durability.SYNC);                             // write a tombstone
  try (Cursor c = db.scan(from, to)) {                         // [from, to); null = unbounded
    for (; c.isValid(); c.next()) {
      use(c.key(), c.value());
    }
  }
}                                                              // close() releases files
```

| Method | What it does |
|---|---|
| `Shale.open(dir, clock, metrics)` | Opens or creates a database; recovers whatever the write-ahead log holds. `open(dir, clock, metrics, writeBufferSizeBytes)` sets the memtable size (default 4 MiB). |
| `put(key, value, durability)` | Stores `value` under `key`, replacing any older value. Returns once the chosen durability holds (§5). |
| `delete(key, durability)` | Removes `key`. It writes a tombstone, so it costs a write, like a put. |
| `get(key)` | The newest value, or `null`. The returned array is a copy you own. |
| `scan(from, to)` | A cursor over keys in `[from, to)`, in key order. `null` bounds are open-ended. **Always close it**: an open cursor pins files. |
| `close()` | Closes the log and file handles. |

**Keys are ordered bytewise, unsigned** (LevelDB's order): `0x01` < `0x7F` < `0xFF`, and a key
sorts before any longer key it is a prefix of. So encode integers **big-endian** if you want them
to sort numerically in scans; the default little-endian bytes of most libraries will not.

## 5. Durability: what each mode promises

Every write names its guarantee; there is no default to forget.

| Mode | Returns after | Survives a process crash | Survives power loss |
|---|---|---|---|
| `NONE` | the write is in the OS page cache | yes | **no** — the last writes can be lost |
| `SYNC` | the write-ahead log is fsynced | yes | yes |
| `GROUP` | same as `SYNC` today; M5.5 shares one fsync across concurrent writers | yes | yes |

**How these claims are tested:**
- recovery is run from a write-ahead log truncated at **every byte offset**, and must return
  exactly the complete records;
- every SSTable is corrupted at **every byte offset**, and each corruption must be detected;
- a model test runs thousands of random operations with restarts against a `TreeMap`.

From M5, a fault-injection filesystem also simulates power loss at every file operation.

## 6. Threading

- **`Shale`** is thread-safe. Writes are serialised internally; reads never block and never take
  a lock.
- **A `Cursor`** belongs to the thread that opened it.
- **A cursor sees a stable view** of the database as it was when opened, including across
  flushes.

## 7. Errors

| Exception (all unchecked) | Means | What to do |
|---|---|---|
| `CorruptionException` | bytes on disk are wrong (a checksum mismatch, a bad structure) | stop, and keep the directory for inspection. Shale never "repairs" silently |
| `StorageException` | the environment failed: I/O error, disk full, permissions | fix the environment; reopen. The write that threw was not acknowledged |
| `EngineStateException` | the engine cannot serve the request now | today: not thrown. From M5: closed, or failed after an I/O error — reopen |
| `IllegalArgumentException` | a caller bug, such as a `null` key | fix the calling code |

## 8. Metrics

Pass your own `Metrics` implementation to `open` to receive counters. Emitted today:

| Name | Kind | Meaning |
|---|---|---|
| `wal.append.count` | counter | records appended to the write-ahead log |
| `wal.sync.count`, `wal.sync.duration` | counter, histogram (ns) | fsyncs and their latency |
| `memtable.size.bytes` | gauge | bytes in the active memtable |
| `memtable.switch.count` | counter | memtables frozen for flushing |
| `flush.count`, `flush.bytes` | counters | memtables written to SSTables |

M6 adds amplification counters and compaction debt; the full list will live here.

## 9. On disk

Shale owns its directory. Do not put other files there.

| File | What |
|---|---|
| `NNNNNN.wal` | write-ahead log segments |
| `NNNNNN.sst` | SSTables: immutable, sorted tables |
| `*.tmp` | an SSTable being written; deleted at the next open |

**Backups today:** close the engine (or stop your application), then copy the directory. A copy
taken while the engine runs is not guaranteed consistent. M8 adds `Shale.checkpoint(dir)`, an
online consistent backup.

## 10. Current limitations — read before relying on it

| Limitation | Lifts at |
|---|---|
| **Disk use only grows:** there is no compaction, so no SSTable is ever deleted, and every flush adds one | M6 (compaction), M5 (safe deletion) |
| **Reads slow down as tables accumulate:** a missing key is looked for in every SSTable | M6 (compaction), M7 (bloom filters) |
| **Two processes opening one directory will corrupt it:** there is no `LOCK` file yet | M5 |
| **A flush blocks writers** while it writes and fsyncs a table | M5.5 |
| **No atomic multi-key writes, and no snapshots** | M7 |
| **No enforced key or value size limits.** Keep keys to a few KiB and values to a few MiB; explicit limits arrive with M7 | M7 |
| **After `close()`, calls are not rejected** | M5 |
| **No online backup** | M8 |

## 11. Compatibility

- **On-disk formats** are versioned. Every change bumps a version, keeps reading the old format,
  and is guarded by a checked-in golden file (`on-disk-formats.md`). A database written by an
  older build opens in a newer one. The one planned exception is the one-time migration of M4
  directories at M5, which is automatic.
- **The Java API** may still change before Shale 1.0 (M8). Methods shown here will keep working;
  new ones are added beside them. After 1.0, anything outside `dev.shale.internal` follows
  semantic versioning.

## Where next

- **How it works inside:** [`documentation/architecture/`](../architecture/README.md).
- **Why it is built this way:** [the ADRs](../adr/README.md).
- **Questions a user or an interviewer would ask:** [the FAQ](../faq.md).
