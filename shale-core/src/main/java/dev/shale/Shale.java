package dev.shale;

import dev.shale.env.Env;
import dev.shale.internal.annotations.ThreadSafe;
import dev.shale.internal.key.InternalKey;
import dev.shale.internal.key.InternalKeyComparator;
import dev.shale.internal.key.ValueType;
import dev.shale.internal.log.LogReadResult;
import dev.shale.iterator.InternalIterator;
import dev.shale.iterator.MergingIterator;
import dev.shale.iterator.ReconcilingCursor;
import dev.shale.memtable.Memtable;
import dev.shale.memtable.SkiplistMemtable;
import dev.shale.sstable.SSTableReader;
import dev.shale.sstable.SSTableWriter;
import dev.shale.wal.RecoveryPolicy;
import dev.shale.wal.WalReader;
import dev.shale.wal.WalRecordCodec;
import dev.shale.wal.WalWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The single-node LSM engine (M3 slice): every mutation is logged to the WAL before it is applied
 * to an in-memory {@link Memtable}; when the active memtable fills it is switched out and
 * <b>flushed</b> to an immutable {@link SSTableReader} on disk, after which its WAL segment is
 * reclaimed.
 *
 * <p><b>Two read paths, deliberately (ADR-0011).</b> {@link #get} probes each source's {@code
 * ceiling} newest-first and stops at the first source holding any version of the key — a point
 * lookup needs no global order, so it builds no merge. {@link #scan} streams a heap merge across
 * every source through a {@link ReconcilingCursor}. They must agree about what is visible; when M7
 * adds snapshot filtering, both learn it.
 *
 * <p><b>Threading:</b> thread-safe. Writers serialise on {@code writeLock} (guarding the WAL, the
 * sequence and file-number counters, and publication of the read view); readers take a single
 * volatile read of the {@link ReadView} and traverse the lock-free skiplists and the thread-safe
 * SSTable readers with no lock (concurrency-and-resources.md §2). Flush is synchronous on the write
 * path at M3 (a background flush thread and write stalls arrive at M5.5); the manifest and the
 * reference-counted delete lifecycle arrive at M5. <b>Ownership:</b> owns a {@link WalWriter} and
 * one reference to each {@link SSTableReader}, all released by {@link #close()}. Every file
 * operation goes through the {@link Env} it was opened with (ADR-0012).
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

  private static final Pattern WAL_SEGMENT = Pattern.compile("\\d{6}\\.wal");
  private static final Pattern SSTABLE_FILE = Pattern.compile("\\d{6}\\.sst");
  private static final String TEMP_SUFFIX = ".tmp";
  private static final byte[] NO_VALUE = new byte[0];

  /**
   * Sentinel from a lookup: "this source has no version of the key" (vs. a value, or null=deleted).
   */
  private static final byte[] FALL_THROUGH = new byte[0];

  /**
   * Fixed seed for skiplist tower heights; structure is seed-independent, so this keeps tests
   * reproducible.
   */
  private static final long SKIPLIST_SEED = 0x5EED_5EEDL;

  private final Object writeLock = new Object();
  private final Env env;
  private final Path directory;
  private final Clock clock;
  private final Metrics metrics;
  private final long writeBufferSizeBytes;
  private final KeyComparator userComparator;
  private final InternalKeyComparator ordering;

  /** The active WAL segment writer; rolled on a switch. @GuardedBy("writeLock") */
  private WalWriter wal;

  /**
   * The active WAL segment's path, reclaimed once its memtable is flushed. @GuardedBy("writeLock")
   */
  private Path activeWalSegment;

  /** One monotonic counter for every file kind (naming.md §7). @GuardedBy("writeLock") */
  private long nextFileNumber;

  /** Next sequence number to stamp. @GuardedBy("writeLock") */
  private long nextSequence;

  /** The live read view. Volatile: one read publishes a consistent snapshot to a reader. */
  private volatile ReadView view;

  /**
   * Set once by {@link #close()}; every later call is rejected. Volatile so a reader sees it
   * without the lock; written under {@code writeLock}.
   */
  private volatile boolean closed;

  private Shale(
      Env env,
      Path directory,
      Clock clock,
      Metrics metrics,
      long writeBufferSizeBytes,
      KeyComparator userComparator,
      InternalKeyComparator ordering,
      WalWriter wal,
      Path activeWalSegment,
      long nextFileNumber,
      long nextSequence,
      ReadView view) {
    this.env = env;
    this.directory = directory;
    this.clock = clock;
    this.metrics = metrics;
    this.writeBufferSizeBytes = writeBufferSizeBytes;
    this.userComparator = userComparator;
    this.ordering = ordering;
    this.wal = wal;
    this.activeWalSegment = activeWalSegment;
    this.nextFileNumber = nextFileNumber;
    this.nextSequence = nextSequence;
    this.view = view;
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
   * ShaleOptions#env()}: a test passes a fault-injecting one (ADR-0012). The active memtable is
   * flushed to an SSTable once it reaches {@link ShaleOptions#writeBufferSizeBytes()}. Recovery
   * loads the existing SSTables, replays any WAL into a memtable, flushes that memtable to a fresh
   * SSTable, and deletes the replayed segments — so a reopen leaves the log empty and all recovered
   * data in tables.
   */
  public static Shale open(Path directory, ShaleOptions options, Clock clock, Metrics metrics)
      throws IOException {
    Env env = options.env();
    long writeBufferSizeBytes = options.writeBufferSizeBytes();
    env.createDirectories(directory);
    KeyComparator comparator = BytewiseComparator.INSTANCE;
    InternalKeyComparator ordering = new InternalKeyComparator(comparator);
    deleteTempTables(env, directory); // clean any incomplete table from a crashed flush

    // Load complete SSTables oldest→newest, building a newest-first live list.
    List<SSTableReader> sstablesNewestFirst = new ArrayList<>();
    long maxSequence = 0;
    long maxFileNumber = 0;
    for (Path table : filesInOrder(env, directory, SSTABLE_FILE)) {
      SSTableReader reader = SSTableReader.open(env, table, ordering);
      sstablesNewestFirst.add(0, reader);
      maxSequence = Math.max(maxSequence, maxSequenceOf(reader));
      maxFileNumber = Math.max(maxFileNumber, numberOf(table));
    }

    // Replay any WAL segments into one recovery memtable. Every throw from here on happens before
    // the recovery flush writes a table or any segment is deleted, so a rejected open leaves every
    // input as it was (ADR-0024 §4).
    Memtable recovered = new SkiplistMemtable(comparator, SKIPLIST_SEED);
    List<Path> walSegments = filesInOrder(env, directory, WAL_SEGMENT);
    for (int i = 0; i < walSegments.size(); i++) {
      Path segment = walSegments.get(i);
      for (byte[] payload : replay(env, segment, i == walSegments.size() - 1)) {
        WalRecordCodec.Decoded decoded = WalRecordCodec.decode(payload);
        recovered.add(decoded.internalKey().encode(), decoded.value());
        maxSequence = Math.max(maxSequence, decoded.internalKey().sequenceNumber());
      }
      maxFileNumber = Math.max(maxFileNumber, numberOf(segment));
    }

    long nextFileNumber = maxFileNumber + 1;
    if (recovered.sizeBytes() > 0) {
      Path table = directory.resolve(sstableName(nextFileNumber++));
      sstablesNewestFirst.add(0, flushToSSTable(env, table, recovered, ordering));
      countFlush(metrics, recovered);
    }
    for (Path segment : walSegments) {
      env.delete(segment); // D3: the recovery table is durable before its log is dropped
    }

    Path activeSegment = directory.resolve(segmentName(nextFileNumber++));
    WalWriter wal = createSegment(env, activeSegment, clock, metrics);
    Memtable active = new SkiplistMemtable(comparator, SKIPLIST_SEED);
    ReadView view = new ReadView(active, List.of(), List.copyOf(sstablesNewestFirst));
    return new Shale(
        env,
        directory,
        clock,
        metrics,
        writeBufferSizeBytes,
        comparator,
        ordering,
        wal,
        activeSegment,
        nextFileNumber,
        maxSequence + 1,
        view);
  }

  /**
   * {@inheritDoc}
   *
   * <p><b>When it throws {@link StorageException}, the outcome is unknown.</b> The record is
   * appended to the WAL and applied to the memtable <em>before</em> a full memtable is flushed; if
   * that flush fails, this call throws although the write was logged (to {@code durability}) and is
   * readable. Close and reopen before writing again: nothing yet stops further writes after a
   * failed flush, and continuing can make {@link #get} and {@link #scan} disagree (SH-01; M5 adds
   * the failed state).
   */
  @Override
  public void put(byte[] userKey, byte[] value, Durability durability) {
    requireArgs(userKey, value, durability);
    synchronized (writeLock) {
      requireOpen();
      append(new InternalKey(userKey, nextSequence++, ValueType.PUT), value, durability);
    }
  }

  /** {@inheritDoc} A {@link StorageException} has the same unknown outcome as for {@link #put}. */
  @Override
  public void delete(byte[] userKey, Durability durability) {
    if (userKey == null || durability == null) {
      throw new IllegalArgumentException("userKey and durability must be non-null");
    }
    synchronized (writeLock) {
      requireOpen();
      append(new InternalKey(userKey, nextSequence++, ValueType.DELETE), NO_VALUE, durability);
    }
  }

  private void append(InternalKey key, byte[] value, Durability durability) {
    try {
      wal.append(WalRecordCodec.encode(key, value), durability); // D3: WAL durable before memtable
      Memtable active = view.active();
      active.add(key.encode(), value);
      metrics.gauge("memtable.size.bytes", active.sizeBytes());
      if (active.sizeBytes() >= writeBufferSizeBytes) {
        switchAndFlush();
      }
    } catch (IOException e) {
      throw new StorageException("write failed", e);
    }
  }

  /**
   * Freezes the full active memtable, flushes it to an SSTable, and reclaims its WAL
   * segment. @GuardedBy("writeLock"), reached only from {@link #append}. Synchronous at M3.
   */
  private void switchAndFlush() throws IOException {
    wal.close();
    Memtable frozen = view.active();
    Path frozenSegment = activeWalSegment;
    List<SSTableReader> sstables = view.sstablesNewestFirst();

    // Roll to a fresh active memtable and WAL segment, and publish the frozen memtable as immutable
    // so readers keep seeing its data while the flush runs.
    activeWalSegment = directory.resolve(segmentName(nextFileNumber++));
    wal = createSegment(env, activeWalSegment, clock, metrics);
    Memtable active = new SkiplistMemtable(userComparator, SKIPLIST_SEED);
    view = new ReadView(active, List.of(frozen), sstables);
    metrics.increment("memtable.switch.count", 1);

    Path table = directory.resolve(sstableName(nextFileNumber++));
    SSTableReader flushed = flushToSSTable(env, table, frozen, ordering);
    countFlush(metrics, frozen);
    view = new ReadView(active, List.of(), prepend(flushed, sstables));
    // D3: the SSTable is durable (force in finish) and installed (atomic rename) before the WAL
    // segment that also held this data is deleted.
    env.delete(frozenSegment);
  }

  /**
   * Creates WAL segment {@code path} and makes its creation durable before any record is appended:
   * {@link WalWriter#open} syncs the header, then the directory is synced. Without the directory
   * sync a power loss can drop the whole segment, synced records and all, because a file's own sync
   * does not persist its name (SH-04).
   */
  private static WalWriter createSegment(Env env, Path path, Clock clock, Metrics metrics)
      throws IOException {
    WalWriter wal = WalWriter.open(env, path, clock, metrics);
    try {
      // DURABILITY: the segment's directory entry; with it, a SYNC record in it survives power
      // loss.
      env.syncDirectory(path.getParent());
    } catch (IOException e) {
      wal.close();
      throw e;
    }
    return wal;
  }

  /**
   * Writes a memtable to {@code finalPath} via a temp file, a sync, an atomic rename and a
   * directory sync, and opens a reader. When it returns the table survives power loss under its
   * final name.
   */
  private static SSTableReader flushToSSTable(
      Env env, Path finalPath, Memtable memtable, InternalKeyComparator ordering)
      throws IOException {
    Path tempPath = finalPath.resolveSibling(finalPath.getFileName() + TEMP_SUFFIX);
    try (SSTableWriter writer = SSTableWriter.open(env, tempPath);
        InternalIterator entries = memtable.iterator()) {
      // Streamed, not materialised: a flush that first copied the whole memtable into a list
      // would double its peak footprint at exactly the moment memory pressure triggered it.
      for (entries.seekToFirst(); entries.valid(); entries.next()) {
        writer.add(entries.internalKey(), entries.value());
      }
      writer.finish(); // fsync
    }
    env.rename(tempPath, finalPath); // install atomically
    // DURABILITY: the table's new name. Only now may the WAL segment holding the same data be
    // deleted: an unsynced rename can be lost while a later delete is kept (SH-04, D3).
    env.syncDirectory(finalPath.getParent());
    return SSTableReader.open(env, finalPath, ordering);
  }

  private static void countFlush(Metrics metrics, Memtable flushed) {
    metrics.increment("flush.count", 1);
    metrics.increment("flush.bytes", flushed.sizeBytes());
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
    ReadView snapshot = view; // one volatile read

    // Memtables are newer than any SSTable; within each group, newest first. The first source
    // holding any version of the key holds its newest version (sequence-boundary property).
    for (Memtable memtable : snapshot.memtablesNewestFirst()) {
      byte[] resolved = resolve(memtable.ceiling(lookup), userKey);
      if (resolved != FALL_THROUGH) {
        return resolved;
      }
    }
    for (SSTableReader table : snapshot.sstablesNewestFirst()) {
      SSTableReader.Entry entry = table.ceiling(lookup);
      byte[] resolved =
          entry == null ? FALL_THROUGH : resolve(entry.internalKey(), entry.value(), userKey);
      if (resolved != FALL_THROUGH) {
        return resolved;
      }
    }
    return null;
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
   * memtable set and every SSTable (ADR-0011).
   *
   * <p>The traversal costs the result, not the database: each source seeks straight to the lower
   * bound, the heap merge yields entries one at a time, and the scan stops at the upper bound.
   * Until M4 this decoded every entry in every source into one list and sorted it, so a one-key
   * scan of a million keys paid for a million entries. <b>Source setup is still per table:</b>
   * every SSTable's iterator is built and positioned, loading one data block each, even when the
   * range holds no key of that table (SH-17); M6 selects tables by key range first.
   *
   * <p>The cursor is weakly consistent: see {@link Cursor}.
   *
   * <p><b>The returned cursor must be closed.</b> It pins every SSTable it can read from for its
   * whole life (N6); leaking one leaks file handles.
   */
  @Override
  public Cursor scan(byte[] fromInclusive, byte[] toExclusive) {
    requireOpen();
    ReadView snapshot =
        view; // one volatile read: a stable set of sources (not a sequence snapshot)
    List<InternalIterator> sources = new ArrayList<>();
    for (Memtable memtable : snapshot.memtablesNewestFirst()) {
      sources.add(memtable.iterator());
    }
    for (SSTableReader table : snapshot.sstablesNewestFirst()) {
      sources.add(table.iterator());
    }
    // Source order only breaks ties the merge cannot actually see — sequence numbers are unique,
    // so precedence is already in the keys. It is kept newest-first to match the read view.
    return new ReconcilingCursor(
        new MergingIterator(sources, ordering),
        userComparator,
        fromInclusive,
        toExclusive,
        List.copyOf(snapshot.sstablesNewestFirst()));
  }

  @Override
  public KeyComparator comparator() {
    return userComparator;
  }

  /**
   * Closes the engine: syncs and closes the WAL and drops the engine's reference to every table.
   * Idempotent: a second call does nothing. Afterwards {@code put}, {@code delete}, {@code get} and
   * {@code scan} throw {@link EngineStateException}; a cursor opened before keeps reading, because
   * it holds its own reference to every table it reads.
   */
  @Override
  public void close() {
    synchronized (writeLock) {
      if (closed) {
        return;
      }
      closed = true;
      try {
        wal.close();
      } catch (IOException e) {
        throw new StorageException("closing the WAL failed", e);
      } finally {
        for (SSTableReader table : view.sstablesNewestFirst()) {
          table.release();
        }
      }
    }
  }

  private void requireOpen() {
    if (closed) {
      throw new EngineStateException("engine is closed");
    }
  }

  private static void requireArgs(byte[] userKey, byte[] value, Durability durability) {
    if (userKey == null || value == null || durability == null) {
      throw new IllegalArgumentException("userKey, value, and durability must be non-null");
    }
  }

  /**
   * The records of one WAL segment, under the engine's recovery policy ({@link
   * RecoveryPolicy#TRUNCATE_TAIL}) and ADR-0024 §2's rule that <b>only the newest segment may end
   * torn</b>. Every older segment was synced when it was closed (see {@link WalWriter#close()}), so
   * a crash cannot have cut it short; if one ends torn, its bytes were lost or damaged some other
   * way, and dropping its tail would silently lose writes. That is corruption (N4).
   */
  private static List<byte[]> replay(Env env, Path segment, boolean newest) throws IOException {
    LogReadResult result = WalReader.read(env, segment);
    if (!newest && result.tail() != LogReadResult.Tail.CLEAN) {
      throw new CorruptionException(
          "WAL segment "
              + segment.getFileName()
              + " ends torn ("
              + result.tail()
              + ") but is not the newest: only the newest segment can be cut short by a crash",
          result.tailOffset(),
          -1,
          -1);
    }
    return result.records();
  }

  /**
   * The highest sequence number stored in {@code table}, to resume stamping above it on reopen.
   *
   * <p>Every entry is examined because the internal-key order is user key ascending, not sequence
   * ordered — the newest mutation in a table can sit anywhere in it. Streaming keeps that scan's
   * cost to one data block at a time; it used to decode the entire table into a list first.
   */
  private static long maxSequenceOf(SSTableReader table) {
    long max = 0;
    try (InternalIterator entries = table.iterator()) {
      for (entries.seekToFirst(); entries.valid(); entries.next()) {
        max = Math.max(max, InternalKey.decode(entries.internalKey()).sequenceNumber());
      }
    }
    return max;
  }

  private static List<Path> filesInOrder(Env env, Path directory, Pattern namePattern)
      throws IOException {
    return env.children(directory).stream()
        .filter(name -> namePattern.matcher(name).matches())
        .sorted(Comparator.naturalOrder())
        .map(directory::resolve)
        .toList();
  }

  private static void deleteTempTables(Env env, Path directory) throws IOException {
    for (String name : env.children(directory)) {
      if (name.endsWith(TEMP_SUFFIX)) {
        env.delete(directory.resolve(name));
      }
    }
  }

  private static int numberOf(Path file) {
    return Integer.parseInt(file.getFileName().toString().substring(0, 6));
  }

  private static String segmentName(long number) {
    return String.format("%06d.wal", number);
  }

  private static String sstableName(long number) {
    return String.format("%06d.sst", number);
  }

  private static List<SSTableReader> prepend(SSTableReader table, List<SSTableReader> tail) {
    List<SSTableReader> out = new ArrayList<>(tail.size() + 1);
    out.add(table);
    out.addAll(tail);
    return List.copyOf(out);
  }

  /**
   * The live read view: the active memtable, zero or more immutable memtables awaiting flush
   * (newest first), and the SSTables (newest first). Immutable so publishing a new view is one
   * volatile write.
   *
   * @param active the memtable receiving writes
   * @param immutablesNewestFirst frozen memtables mid-flush, newest first
   * @param sstablesNewestFirst on-disk tables, newest (most recently flushed) first
   */
  private record ReadView(
      Memtable active,
      List<Memtable> immutablesNewestFirst,
      List<SSTableReader> sstablesNewestFirst) {

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
