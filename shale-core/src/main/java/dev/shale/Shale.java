package dev.shale;

import dev.shale.env.Env;
import dev.shale.internal.annotations.ThreadSafe;
import dev.shale.internal.key.InternalKey;
import dev.shale.internal.key.InternalKeyComparator;
import dev.shale.internal.key.ValueType;
import dev.shale.iterator.InternalIterator;
import dev.shale.iterator.MergingIterator;
import dev.shale.iterator.ReconcilingCursor;
import dev.shale.manifest.FileMetadata;
import dev.shale.manifest.Version;
import dev.shale.manifest.VersionEdit;
import dev.shale.manifest.VersionSet;
import dev.shale.memtable.Memtable;
import dev.shale.memtable.SkiplistMemtable;
import dev.shale.sstable.SSTableReader;
import dev.shale.sstable.SSTableWriter;
import dev.shale.wal.WalRecordCodec;
import dev.shale.wal.WalWriter;
import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The single-node LSM engine: every mutation is logged to the WAL before it is applied to an
 * in-memory {@link Memtable}; when the active memtable fills it is switched out and <b>flushed</b>
 * to an immutable SSTable, which a manifest edit then makes part of the durable set of live files
 * (ADR-0012). Only then is the WAL segment that held the same data deleted.
 *
 * <p><b>The manifest is the authority.</b> The live tables are what the {@link VersionSet}'s
 * manifest says, not what the directory holds: a file the manifest does not name is an orphan from
 * an interrupted operation, deleted at the next open. That is what makes deleting a file safe.
 *
 * <p><b>Two read paths, deliberately (ADR-0011).</b> {@link #get} probes each source's {@code
 * ceiling} newest-first and stops at the first source holding any version of the key — a point
 * lookup needs no global order, so it builds no merge. {@link #scan} streams a heap merge across
 * every source through a {@link ReconcilingCursor}. Both read the tables of a {@link Version} they
 * have retained, so no table they read can be deleted under them.
 *
 * <p><b>Failure.</b> If a flush cannot complete, the engine enters the <em>failed state</em>:
 * writes throw {@link EngineStateException} naming the cause, reads go on serving the last
 * published view, and only {@link #close()} and a reopen recover (ADR-0012 §2.6; SH-01).
 *
 * <p><b>Threading:</b> thread-safe. Writers serialise on {@code writeLock} (guarding the WAL, the
 * sequence counter, flush, and publication of the read view); the {@code VersionSet}'s own lock
 * ranks below it. Readers take one volatile read of the {@link ReadView}, then retain the current
 * Version, and traverse the lock-free skiplists and the thread-safe SSTable readers with no lock
 * (concurrency-and-resources.md §2). Flush is synchronous on the write path until M5.5.
 * <b>Ownership:</b> owns the {@code LOCK}, the active {@link WalWriter} and the {@link VersionSet},
 * all released by {@link #close()}. Every file operation goes through the {@link Env} it was opened
 * with.
 *
 * @see "Petrov, Database Internals, ch. 5 (recovery) and ch. 7 (LSM flush)"
 * @see <a href="https://github.com/google/leveldb/blob/main/db/db_impl.cc">LevelDB db_impl.cc</a>
 */
@ThreadSafe
public final class Shale implements StorageBackend {

  /**
   * Default memtable size that triggers a flush: 4 MiB, matching LevelDB's {@code
   * write_buffer_size}. The same value as {@link ShaleOptions#defaults()}.
   */
  public static final long DEFAULT_WRITE_BUFFER_SIZE_BYTES =
      ShaleOptions.defaults().writeBufferSizeBytes();

  static final String LOCK_FILE = "LOCK";
  static final String TEMP_SUFFIX = ".tmp";

  /**
   * Fixed seed for skiplist tower heights; structure is seed-independent, so this keeps tests
   * reproducible.
   */
  static final long SKIPLIST_SEED = 0x5EED_5EEDL;

  private static final byte[] NO_VALUE = new byte[0];

  /**
   * Sentinel from a lookup: "this source has no version of the key" (vs. a value, or null=deleted).
   */
  private static final byte[] FALL_THROUGH = new byte[0];

  private final Object writeLock = new Object();
  private final Env env;
  private final Closeable lock;
  private final Path directory;
  private final Clock clock;
  private final Metrics metrics;
  private final ShaleOptions options;
  private final KeyComparator userComparator;
  private final InternalKeyComparator ordering;
  private final VersionSet versions;

  /** The active WAL segment writer; null once closed. @GuardedBy("writeLock") */
  private WalWriter wal;

  /** The active WAL segment's number. @GuardedBy("writeLock") */
  private long walNumber;

  /** Next sequence number to stamp. @GuardedBy("writeLock") */
  private long nextSequence;

  /** Why the engine stopped accepting writes, or null. @GuardedBy("writeLock") */
  private Throwable failure;

  /** The live memtables. Volatile: one read publishes a consistent set to a reader. */
  private volatile ReadView view;

  /**
   * Set once by {@link #close()}; every later call is rejected. Volatile so a reader sees it
   * without the lock; written under {@code writeLock}.
   */
  private volatile boolean closed;

  Shale(Recovery.Opened opened) {
    this.env = opened.env();
    this.lock = opened.lock();
    this.directory = opened.directory();
    this.clock = opened.clock();
    this.metrics = opened.metrics();
    this.options = opened.options();
    this.userComparator = opened.userComparator();
    this.ordering = opened.ordering();
    this.versions = opened.versions();
    this.wal = opened.wal();
    this.walNumber = opened.walNumber();
    this.nextSequence = opened.nextSequence();
    this.view = new ReadView(newMemtable(userComparator), List.of());
  }

  /**
   * Opens (or recovers) an engine rooted at {@code directory} with {@link ShaleOptions#defaults()}.
   */
  public static Shale open(Path directory, Clock clock, Metrics metrics) throws IOException {
    return open(directory, ShaleOptions.defaults(), clock, metrics);
  }

  /**
   * Opens (or recovers) an engine with the default options but the given write-buffer size.
   *
   * @see ShaleOptions#writeBufferSizeBytes()
   */
  public static Shale open(Path directory, Clock clock, Metrics metrics, long writeBufferSizeBytes)
      throws IOException {
    return open(
        directory,
        ShaleOptions.defaults().withWriteBufferSizeBytes(writeBufferSizeBytes),
        clock,
        metrics);
  }

  /**
   * Opens (or recovers) an engine with {@code options}, doing every file operation through {@link
   * ShaleOptions#env()}: a test passes a fault-injecting one (ADR-0012).
   *
   * <p>Recovery (ADR-0012 §2.3): take {@code LOCK}; delete {@code *.tmp}; load the live tables from
   * the manifest {@code CURRENT} names (or, in a directory written before M5, from the files
   * present); replay the WAL segments the manifest still needs; flush what they held to a table;
   * write a new manifest holding the whole state and switch {@code CURRENT} to it — the commit
   * point; create a fresh WAL segment; delete every file the new state does not need.
   *
   * <p>A refused open changes no input: nothing is deleted before the commit point, and everything
   * it acquired is released (SH-02). Files it wrote before failing are orphans that the next
   * successful open deletes.
   *
   * @throws EngineStateException if another engine holds the directory
   * @throws CorruptionException if any input is damaged, naming the file
   * @throws IllegalArgumentException if the manifest was written with a different comparator
   */
  public static Shale open(Path directory, ShaleOptions options, Clock clock, Metrics metrics)
      throws IOException {
    options.env().createDirectories(directory);
    // LOCK first: a second engine on this directory would replay and delete the same files.
    Closeable lock = options.env().lock(directory.resolve(LOCK_FILE));
    boolean opened = false;
    try {
      Shale shale =
          new Shale(Recovery.run(new Recovery.Request(directory, options, clock, metrics), lock));
      opened = true;
      return shale;
    } finally {
      if (!opened) {
        lock.close(); // a refused open must not keep the directory locked
      }
    }
  }

  /**
   * {@inheritDoc}
   *
   * <p>If the write fills the memtable, the flush it triggers runs before this returns. The write
   * was already logged to {@code durability} and applied, so <b>it returns normally even if that
   * flush fails</b>: its own guarantee holds. The failure puts the engine in the failed state, and
   * the next write reports it.
   *
   * @throws EngineStateException if the engine is closed or failed
   * @throws StorageException if the WAL append itself fails: the outcome of this write is unknown,
   *     and the engine is failed
   */
  @Override
  public void put(byte[] userKey, byte[] value, Durability durability) {
    requireArgs(userKey, value, durability);
    synchronized (writeLock) {
      requireWritable();
      append(new InternalKey(userKey, nextSequence++, ValueType.PUT), value, durability);
    }
  }

  /** {@inheritDoc} Failures are as for {@link #put}. */
  @Override
  public void delete(byte[] userKey, Durability durability) {
    if (userKey == null || durability == null) {
      throw new IllegalArgumentException("userKey and durability must be non-null");
    }
    synchronized (writeLock) {
      requireWritable();
      append(new InternalKey(userKey, nextSequence++, ValueType.DELETE), NO_VALUE, durability);
    }
  }

  /** Logs, applies, and flushes if the memtable is full. @GuardedBy("writeLock") */
  private void append(InternalKey key, byte[] value, Durability durability) {
    try {
      wal.append(WalRecordCodec.encode(key, value), durability); // D3: WAL durable before memtable
    } catch (IOException e) {
      fail(e); // the WAL's tail is unknown: no later append may follow it
      throw new StorageException("write failed", e);
    }
    Memtable active = view.active();
    active.add(key.encode(), value);
    metrics.gauge("memtable.size.bytes", active.sizeBytes());
    if (active.sizeBytes() >= options.writeBufferSizeBytes()) {
      try {
        switchAndFlush();
      } catch (IOException e) {
        fail(e); // this write is durable to its mode and applied; the next one is refused
      }
    }
  }

  /**
   * Freezes the full active memtable, flushes it to a table, commits the table through the
   * manifest, and only then reclaims its WAL segment (ADR-0012 §2.4). @GuardedBy("writeLock").
   */
  private void switchAndFlush() throws IOException {
    // 1. Switch: a fresh segment and memtable; the frozen one stays readable as an immutable.
    Memtable frozen = view.active();
    long frozenNumber = walNumber;
    WalWriter closing = wal;
    wal = null;
    closing.close(); // syncs: a closed segment is complete (ADR-0024 §5)
    walNumber = versions.newFileNumber();
    wal = Recovery.createSegment(env, directory.resolve(FileNames.wal(walNumber)), clock, metrics);
    Memtable active = newMemtable(userComparator);
    view = new ReadView(active, List.of(frozen));
    metrics.increment("memtable.switch.count", 1);

    // 2. The table: written, synced, renamed, and its name synced.
    long tableNumber = versions.newFileNumber();
    Flushed flushed =
        flush(env, directory.resolve(FileNames.sstable(tableNumber)), frozen, ordering);
    try {
      // 3. Commit: the edit's sync is the durability point of the flush (ManifestWriter.log).
      VersionEdit edit =
          VersionEdit.builder()
              .addFile(flushed.metadata())
              .logNumber(walNumber)
              .lastSequence(nextSequence - 1)
              .build();
      versions.logAndApply(edit, Map.of(tableNumber, flushed.reader()));
    } finally {
      flushed.reader().release(); // the Version holds it now, or nobody does
    }
    countFlush(metrics, frozen);

    // 4. Publish: the frozen memtable's data is in the current Version.
    view = new ReadView(active, List.of());

    // 5. Clean up: the segment is no longer needed by the durable state.
    Recovery.deleteCounted(env, directory.resolve(FileNames.wal(frozenNumber)), metrics);

    // 6. A long-lived database replays a short manifest.
    versions.rolloverIfLargerThan(options.manifestRolloverBytes());
  }

  /** Enters the failed state (ADR-0012 §2.6). @GuardedBy("writeLock") */
  private void fail(Throwable cause) {
    if (failure == null) {
      failure = cause;
      metrics.increment("engine.failed.count", 1);
    }
  }

  /**
   * The value for {@code userKey}, or null if absent or deleted.
   *
   * <p>Probes each source newest-first and returns as soon as one holds any version of the key.
   * This deliberately does not go through the merge iterator {@link #scan} uses: a point lookup
   * wants the first source with the key, not a globally ordered stream, so constructing and
   * heapifying a merge would cost more for no answer it does not already have. LevelDB splits the
   * paths for the same reason (ADR-0011).
   */
  @Override
  public byte[] get(byte[] userKey) {
    if (userKey == null) {
      throw new IllegalArgumentException("userKey is null");
    }
    requireOpen();
    byte[] lookup = new InternalKey(userKey, InternalKey.MAX_SEQUENCE, ValueType.FOR_SEEK).encode();
    // The view first, then the Version: a flush installs its Version before it publishes the view
    // without the frozen memtable, so the Version retained here holds everything that view lacks.
    ReadView snapshot = view;

    // Memtables are newer than any SSTable; within each group, newest first. The first source
    // holding any version of the key holds its newest version (sequence-boundary property).
    for (Memtable memtable : snapshot.memtablesNewestFirst()) {
      byte[] resolved = resolve(memtable.ceiling(lookup), userKey);
      if (resolved != FALL_THROUGH) {
        return resolved;
      }
    }
    Version version = versions.acquire();
    try {
      for (SSTableReader table : version.tablesNewestFirst()) {
        SSTableReader.Entry entry = table.ceiling(lookup);
        byte[] resolved =
            entry == null ? FALL_THROUGH : resolve(entry.internalKey(), entry.value(), userKey);
        if (resolved != FALL_THROUGH) {
          return resolved;
        }
      }
      return null;
    } finally {
      version.release();
    }
  }

  private byte[] resolve(Memtable.Entry entry, byte[] userKey) {
    return entry == null ? FALL_THROUGH : resolve(entry.internalKey(), entry.value(), userKey);
  }

  private byte[] resolve(byte[] internalKey, byte[] value, byte[] userKey) {
    InternalKey found = InternalKey.decode(internalKey);
    if (userComparator.compare(found.userKey(), userKey) != 0) {
      return FALL_THROUGH; // this source has no version of the key; an older one might
    }
    return found.valueType() == ValueType.PUT ? value.clone() : null; // tombstone → deleted
  }

  /**
   * A streaming cursor over the live keys in {@code [fromInclusive, toExclusive)}, merging the
   * memtables and every table of the current Version (ADR-0011).
   *
   * <p>The traversal costs the result, not the database: each source seeks straight to the lower
   * bound, the heap merge yields entries one at a time, and the scan stops at the upper bound.
   * <b>Source setup is still per table:</b> every SSTable's iterator is built and positioned,
   * loading one data block each, even when the range holds no key of that table (SH-17); M6 selects
   * tables by key range first.
   *
   * <p>The cursor is weakly consistent: see {@link Cursor}.
   *
   * <p><b>The returned cursor must be closed.</b> It holds the Version it reads for its whole life
   * (N6), so a leaked cursor keeps every table of that Version open — and on disk.
   */
  @Override
  public Cursor scan(byte[] fromInclusive, byte[] toExclusive) {
    requireOpen();
    ReadView snapshot = view; // one volatile read; the Version after it, as in get
    Version version = versions.acquire();
    boolean adopted = false;
    try {
      List<InternalIterator> sources = new ArrayList<>();
      for (Memtable memtable : snapshot.memtablesNewestFirst()) {
        sources.add(memtable.iterator());
      }
      for (SSTableReader table : version.tablesNewestFirst()) {
        sources.add(table.iterator());
      }
      // Source order only breaks ties the merge cannot actually see — sequence numbers are
      // unique, so precedence is already in the keys. It is kept newest-first to match get.
      MergingIterator merged = new MergingIterator(sources, ordering);
      adopted = true; // from here the cursor owns the reference, even if it throws (SH-10)
      return new ReconcilingCursor(merged, userComparator, fromInclusive, toExclusive, version);
    } finally {
      if (!adopted) {
        version.release();
      }
    }
  }

  @Override
  public KeyComparator comparator() {
    return userComparator;
  }

  /**
   * Closes the engine: syncs and closes the WAL, closes the manifest and drops the engine's
   * reference to the current Version, and releases {@code LOCK}. Idempotent: a second call does
   * nothing. Afterwards {@code put}, {@code delete}, {@code get} and {@code scan} throw {@link
   * EngineStateException}; a cursor opened before keeps reading, because it holds its own Version.
   * Tables stay on disk: closing makes nothing obsolete.
   */
  @Override
  public void close() {
    synchronized (writeLock) {
      if (closed) {
        return;
      }
      closed = true;
      StorageException failed = null;
      for (Closeable resource : closeOrder()) {
        try {
          resource.close();
        } catch (IOException | StorageException e) {
          StorageException wrapped =
              e instanceof StorageException s ? s : new StorageException("closing failed", e);
          if (failed == null) {
            failed = wrapped;
          } else {
            failed.addSuppressed(wrapped);
          }
        }
      }
      if (failed != null) {
        throw failed;
      }
    }
  }

  /** The WAL (if one is open), the manifest and Version, then {@code LOCK}. */
  private List<Closeable> closeOrder() {
    List<Closeable> order = new ArrayList<>();
    if (wal != null) {
      order.add(wal::close);
    }
    order.add(versions::close);
    order.add(lock);
    return order;
  }

  private void requireOpen() {
    if (closed) {
      throw new EngineStateException("engine is closed");
    }
  }

  /** Rejects a write once closed or failed. @GuardedBy("writeLock") */
  private void requireWritable() {
    requireOpen();
    if (failure != null) {
      throw new EngineStateException("engine failed; reopen to recover", failure);
    }
  }

  private static void requireArgs(byte[] userKey, byte[] value, Durability durability) {
    if (userKey == null || value == null || durability == null) {
      throw new IllegalArgumentException("userKey, value, and durability must be non-null");
    }
  }

  static Memtable newMemtable(KeyComparator comparator) {
    return new SkiplistMemtable(comparator, SKIPLIST_SEED);
  }

  /**
   * Writes {@code memtable} to {@code finalPath} via a temp file, a sync, an atomic rename and a
   * directory sync, and opens a reader on it (one reference, the caller's). When it returns the
   * table survives power loss under its final name.
   */
  static Flushed flush(Env env, Path finalPath, Memtable memtable, InternalKeyComparator ordering)
      throws IOException {
    Path tempPath = finalPath.resolveSibling(finalPath.getFileName() + TEMP_SUFFIX);
    FileMetadata metadata;
    try (SSTableWriter writer = SSTableWriter.open(env, tempPath);
        InternalIterator entries = memtable.iterator()) {
      // Streamed, not materialised: a flush that first copied the whole memtable into a list
      // would double its peak footprint at exactly the moment memory pressure triggered it.
      for (entries.seekToFirst(); entries.valid(); entries.next()) {
        writer.add(entries.internalKey(), entries.value());
      }
      writer.finish(); // syncs the table's bytes
      metadata =
          new FileMetadata(
              0,
              FileNames.numberOf(finalPath.getFileName().toString(), FileNames.SSTABLE),
              writer.fileSizeBytes(),
              writer.smallestSequence(),
              writer.largestSequence(),
              writer.smallestKey(),
              writer.largestKey());
    }
    env.rename(tempPath, finalPath); // install atomically
    // DURABILITY: the table's new name. Only now may anything that depends on it be made durable —
    // the manifest edit naming it, or the deletion of the WAL segment holding the same data: an
    // unsynced rename can be lost while a later change is kept (SH-04, D3).
    env.syncDirectory(finalPath.getParent());
    return new Flushed(metadata, SSTableReader.open(env, finalPath, ordering));
  }

  static void countFlush(Metrics metrics, Memtable flushed) {
    metrics.increment("flush.count", 1);
    metrics.increment("flush.bytes", flushed.sizeBytes());
  }

  /**
   * A table just flushed: what the manifest records about it, and a reader holding one reference.
   *
   * @param metadata the table's tag-7 record
   * @param reader a reader on it, whose one reference the caller owns
   */
  record Flushed(FileMetadata metadata, SSTableReader reader) {}

  /**
   * The live memtables: the active one and zero or more immutable ones awaiting flush (newest
   * first). Immutable so publishing a new view is one volatile write. The tables are not here: a
   * reader retains them through the {@link VersionSet}, which cannot hand out a Version whose count
   * has reached zero, whereas retaining one read from a view could race its last release.
   *
   * @param active the memtable receiving writes
   * @param immutablesNewestFirst frozen memtables mid-flush, newest first
   */
  private record ReadView(Memtable active, List<Memtable> immutablesNewestFirst) {

    /**
     * The active memtable followed by the immutables, newest to oldest — the memtable read order.
     */
    List<Memtable> memtablesNewestFirst() {
      if (immutablesNewestFirst.isEmpty()) {
        return List.of(active);
      }
      List<Memtable> all = new ArrayList<>(immutablesNewestFirst.size() + 1);
      all.add(active);
      all.addAll(immutablesNewestFirst);
      return all;
    }
  }
}
