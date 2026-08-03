package dev.shale;

import dev.shale.internal.annotations.ThreadSafe;
import dev.shale.internal.key.InternalKey;
import dev.shale.internal.key.InternalKeyComparator;
import dev.shale.internal.key.ValueType;
import dev.shale.memtable.Memtable;
import dev.shale.memtable.SkiplistMemtable;
import dev.shale.sstable.SSTableReader;
import dev.shale.sstable.SSTableWriter;
import dev.shale.wal.RecoveryPolicy;
import dev.shale.wal.WalReader;
import dev.shale.wal.WalRecordCodec;
import dev.shale.wal.WalWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The single-node LSM engine (M3 slice): every mutation is logged to the WAL before it is applied
 * to an in-memory {@link Memtable}; when the active memtable fills it is switched out and
 * <b>flushed</b> to an immutable {@link SSTableReader} on disk, after which its WAL segment is
 * reclaimed. Reads consult the memtable set and then the SSTables, newest first.
 *
 * <p><b>Threading:</b> thread-safe. Writers serialise on {@code writeLock} (guarding the WAL, the
 * sequence and file-number counters, and publication of the read view); readers take a single
 * volatile read of the {@link ReadView} and traverse the lock-free skiplists and the thread-safe
 * SSTable readers with no lock (concurrency-and-resources.md §2). Flush is synchronous on the write
 * path at M3 (a background flush thread and write stalls arrive at M6); the manifest and the
 * reference-counted delete lifecycle arrive at M5. <b>Ownership:</b> owns a {@link WalWriter} and
 * one reference to each {@link SSTableReader}, all released by {@link #close()}.
 *
 * @see "Petrov, Database Internals, ch. 5 (recovery) and ch. 7 (LSM flush)"
 * @see <a href="https://github.com/google/leveldb/blob/main/db/db_impl.cc">LevelDB db_impl.cc</a>
 */
@ThreadSafe
public final class Shale implements StorageBackend {

  /**
   * Default memtable size that triggers a flush: 4 MiB, matching LevelDB's {@code
   * write_buffer_size}.
   */
  public static final long DEFAULT_WRITE_BUFFER_SIZE_BYTES = 4L * 1024 * 1024;

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

  private Shale(
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
   * Opens (or recovers) an engine rooted at {@code directory} with the default write-buffer size.
   */
  public static Shale open(Path directory, Clock clock, Metrics metrics) throws IOException {
    return open(directory, clock, metrics, DEFAULT_WRITE_BUFFER_SIZE_BYTES);
  }

  /**
   * Opens (or recovers) an engine, flushing the active memtable to an SSTable once it reaches
   * {@code writeBufferSizeBytes}. Recovery loads the existing SSTables, replays any WAL into a
   * memtable, flushes that memtable to a fresh SSTable, and deletes the replayed segments — so a
   * reopen leaves the log empty and all recovered data in tables.
   */
  public static Shale open(Path directory, Clock clock, Metrics metrics, long writeBufferSizeBytes)
      throws IOException {
    if (writeBufferSizeBytes <= 0) {
      throw new IllegalArgumentException("writeBufferSizeBytes must be positive");
    }
    Files.createDirectories(directory);
    KeyComparator comparator = BytewiseComparator.INSTANCE;
    InternalKeyComparator ordering = new InternalKeyComparator(comparator);
    deleteTempTables(directory); // clean any incomplete table from a crashed flush

    // Load complete SSTables oldest→newest, building a newest-first live list.
    List<SSTableReader> sstablesNewestFirst = new ArrayList<>();
    long maxSequence = 0;
    long maxFileNumber = 0;
    for (Path table : filesInOrder(directory, SSTABLE_FILE)) {
      SSTableReader reader = SSTableReader.open(table, ordering);
      sstablesNewestFirst.add(0, reader);
      maxSequence = Math.max(maxSequence, maxSequenceOf(reader));
      maxFileNumber = Math.max(maxFileNumber, numberOf(table));
    }

    // Replay any WAL segments into one recovery memtable.
    Memtable recovered = new SkiplistMemtable(comparator, SKIPLIST_SEED);
    List<Path> walSegments = filesInOrder(directory, WAL_SEGMENT);
    for (Path segment : walSegments) {
      for (byte[] payload : WalReader.readAll(segment, RecoveryPolicy.TRUNCATE_TAIL)) {
        WalRecordCodec.Decoded decoded = WalRecordCodec.decode(payload);
        recovered.add(decoded.internalKey().encode(), decoded.value());
        maxSequence = Math.max(maxSequence, decoded.internalKey().sequenceNumber());
      }
      maxFileNumber = Math.max(maxFileNumber, numberOf(segment));
    }

    long nextFileNumber = maxFileNumber + 1;
    if (recovered.sizeBytes() > 0) {
      Path table = directory.resolve(sstableName(nextFileNumber++));
      sstablesNewestFirst.add(0, flushToSSTable(table, recovered, ordering, metrics));
    }
    for (Path segment : walSegments) {
      Files.delete(segment); // D3: the recovery table is durable before its log is dropped
    }

    Path activeSegment = directory.resolve(segmentName(nextFileNumber++));
    WalWriter wal = WalWriter.open(activeSegment, clock, metrics);
    Memtable active = new SkiplistMemtable(comparator, SKIPLIST_SEED);
    ReadView view = new ReadView(active, List.of(), List.copyOf(sstablesNewestFirst));
    return new Shale(
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

  @Override
  public void put(byte[] userKey, byte[] value, Durability durability) {
    requireArgs(userKey, value, durability);
    synchronized (writeLock) {
      append(new InternalKey(userKey, nextSequence++, ValueType.PUT), value, durability);
    }
  }

  @Override
  public void delete(byte[] userKey, Durability durability) {
    if (userKey == null || durability == null) {
      throw new IllegalArgumentException("userKey and durability must be non-null");
    }
    synchronized (writeLock) {
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
    wal = WalWriter.open(activeWalSegment, clock, metrics);
    Memtable active = new SkiplistMemtable(userComparator, SKIPLIST_SEED);
    view = new ReadView(active, List.of(frozen), sstables);
    metrics.increment("memtable.switch.count", 1);

    Path table = directory.resolve(sstableName(nextFileNumber++));
    SSTableReader flushed = flushToSSTable(table, frozen, ordering, metrics);
    view = new ReadView(active, List.of(), prepend(flushed, sstables));
    // D3: the SSTable is durable (force in finish) and installed (atomic rename) before the WAL
    // segment that also held this data is deleted.
    Files.delete(frozenSegment);
  }

  /** Writes a memtable to {@code finalPath} via a temp file + atomic rename, and opens a reader. */
  private static SSTableReader flushToSSTable(
      Path finalPath, Memtable memtable, InternalKeyComparator ordering, Metrics metrics)
      throws IOException {
    Path tempPath = finalPath.resolveSibling(finalPath.getFileName() + TEMP_SUFFIX);
    try (SSTableWriter writer = SSTableWriter.open(tempPath)) {
      for (Memtable.Entry entry : memtable.entries()) {
        writer.add(entry.internalKey(), entry.value());
      }
      writer.finish(); // fsync
    }
    Files.move(tempPath, finalPath, StandardCopyOption.ATOMIC_MOVE); // install atomically
    metrics.increment("flush.count", 1);
    metrics.increment("flush.bytes", memtable.sizeBytes());
    return SSTableReader.open(finalPath, ordering);
  }

  @Override
  public byte[] get(byte[] userKey) {
    if (userKey == null) {
      throw new IllegalArgumentException("userKey is null");
    }
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

  @Override
  public Cursor scan(byte[] fromInclusive, byte[] toExclusive) {
    ReadView snapshot = view; // one volatile read: a stable view for the whole scan
    List<Memtable.Entry> merged = new ArrayList<>();
    for (Memtable memtable : snapshot.memtablesNewestFirst()) {
      merged.addAll(memtable.entries());
    }
    for (SSTableReader table : snapshot.sstablesNewestFirst()) {
      for (SSTableReader.Entry entry : table.entries()) {
        merged.add(new Memtable.Entry(entry.internalKey(), entry.value()));
      }
    }
    // Sort by internal key (user asc, sequence desc) so the newest version of each user key — from
    // whichever source — sorts first; the streaming heap merge across sources is M4.
    merged.sort(
        (a, b) -> ordering.compare(ByteRange.of(a.internalKey()), ByteRange.of(b.internalKey())));

    List<byte[]> keys = new ArrayList<>();
    List<byte[]> values = new ArrayList<>();
    byte[] previousUserKey = null;
    for (Memtable.Entry entry : merged) {
      InternalKey key = InternalKey.decode(entry.internalKey());
      byte[] userKey = key.userKey();
      if (previousUserKey != null && userComparator.compare(userKey, previousUserKey) == 0) {
        continue; // an older version of a user key already resolved
      }
      previousUserKey = userKey;
      if (outOfRange(userKey, fromInclusive, toExclusive)) {
        continue;
      }
      if (key.valueType() == ValueType.DELETE) {
        continue; // tombstone hides the key
      }
      keys.add(userKey.clone());
      values.add(entry.value().clone());
    }
    return new ListCursor(keys, values);
  }

  private boolean outOfRange(byte[] userKey, byte[] fromInclusive, byte[] toExclusive) {
    if (fromInclusive != null && userComparator.compare(userKey, fromInclusive) < 0) {
      return true;
    }
    return toExclusive != null && userComparator.compare(userKey, toExclusive) >= 0;
  }

  @Override
  public KeyComparator comparator() {
    return userComparator;
  }

  @Override
  public void close() {
    synchronized (writeLock) {
      try {
        wal.close();
      } catch (IOException e) {
        throw new StorageException("closing the WAL failed", e);
      }
      for (SSTableReader table : view.sstablesNewestFirst()) {
        table.release();
      }
    }
  }

  private static void requireArgs(byte[] userKey, byte[] value, Durability durability) {
    if (userKey == null || value == null || durability == null) {
      throw new IllegalArgumentException("userKey, value, and durability must be non-null");
    }
  }

  private static long maxSequenceOf(SSTableReader table) {
    long max = 0;
    for (SSTableReader.Entry entry : table.entries()) {
      max = Math.max(max, InternalKey.decode(entry.internalKey()).sequenceNumber());
    }
    return max;
  }

  private static List<Path> filesInOrder(Path directory, Pattern namePattern) throws IOException {
    try (Stream<Path> entries = Files.list(directory)) {
      return entries
          .filter(path -> namePattern.matcher(path.getFileName().toString()).matches())
          .sorted(Comparator.comparing(path -> path.getFileName().toString()))
          .toList();
    }
  }

  private static void deleteTempTables(Path directory) throws IOException {
    try (Stream<Path> entries = Files.list(directory)) {
      for (Path path :
          entries.filter(p -> p.getFileName().toString().endsWith(TEMP_SUFFIX)).toList()) {
        Files.delete(path);
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

  /** A forward cursor over materialised user keys and values. */
  private static final class ListCursor implements Cursor {
    private final List<byte[]> keys;
    private final List<byte[]> values;
    private int index;

    ListCursor(List<byte[]> keys, List<byte[]> values) {
      this.keys = keys;
      this.values = values;
    }

    @Override
    public boolean isValid() {
      return index < keys.size();
    }

    @Override
    public void next() {
      index++;
    }

    @Override
    public byte[] key() {
      return keys.get(index);
    }

    @Override
    public byte[] value() {
      return values.get(index);
    }

    @Override
    public void close() {
      // nothing to release
    }
  }
}
