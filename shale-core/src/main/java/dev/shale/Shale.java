package dev.shale;

import dev.shale.internal.annotations.ThreadSafe;
import dev.shale.internal.key.InternalKey;
import dev.shale.internal.key.InternalKeyComparator;
import dev.shale.internal.key.ValueType;
import dev.shale.memtable.Memtable;
import dev.shale.memtable.SkiplistMemtable;
import dev.shale.wal.RecoveryPolicy;
import dev.shale.wal.WalReader;
import dev.shale.wal.WalRecordCodec;
import dev.shale.wal.WalWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The single-node LSM engine (M2 slice): every mutation is logged to the WAL before it is applied
 * to an in-memory {@link Memtable}, and {@link #open} replays the log to recover. The active
 * memtable is a hand-written lock-free skiplist (ADR-0009), so reads no longer block writes.
 *
 * <p><b>Threading:</b> thread-safe. Writers serialise on {@code writeLock} (which guards the WAL,
 * the sequence counter, and publication of the memtable set); readers take a single volatile read
 * of the memtable set and traverse the lock-free skiplists with no lock
 * (concurrency-and-resources.md §2). SSTables, flush, and compaction arrive at M3+; until then a
 * memtable switch's immutable memtables accumulate in memory. <b>Ownership:</b> owns a {@link
 * WalWriter} closed by {@link #close()}.
 *
 * @see "Petrov, Database Internals, ch. 5 — write-ahead logging and recovery"
 */
@ThreadSafe
public final class Shale implements StorageBackend {

  /**
   * Default memtable size that triggers a switch: 4 MiB, matching LevelDB's {@code
   * write_buffer_size}.
   */
  public static final long DEFAULT_WRITE_BUFFER_SIZE_BYTES = 4L * 1024 * 1024;

  private static final Pattern SEGMENT_NAME = Pattern.compile("\\d{6}\\.wal");
  private static final byte[] NO_VALUE = new byte[0];

  /**
   * Fixed seed for skiplist tower heights; structure is seed-independent, so this keeps tests
   * reproducible.
   */
  private static final long SKIPLIST_SEED = 0x5EED_5EEDL;

  private final Object writeLock = new Object();
  private final KeyComparator userComparator;
  private final InternalKeyComparator ordering;
  private final Path directory;
  private final Clock clock;
  private final Metrics metrics;
  private final long writeBufferSizeBytes;

  /** The active WAL segment writer; rolled on a memtable switch. @GuardedBy("writeLock") */
  private WalWriter wal;

  /** Number of the next WAL segment to open on a switch. @GuardedBy("writeLock") */
  private int nextSegmentNumber;

  /** The live memtable set. Volatile: one read publishes a consistent snapshot to a reader. */
  private volatile MemtableSet memtables;

  /** Next sequence number to stamp. @GuardedBy("writeLock") */
  private long nextSequence;

  private Shale(
      Path directory,
      Clock clock,
      Metrics metrics,
      long writeBufferSizeBytes,
      KeyComparator userComparator,
      WalWriter wal,
      Memtable active,
      long nextSequence,
      int currentSegmentNumber) {
    this.directory = directory;
    this.clock = clock;
    this.metrics = metrics;
    this.writeBufferSizeBytes = writeBufferSizeBytes;
    this.userComparator = userComparator;
    this.ordering = new InternalKeyComparator(userComparator);
    this.wal = wal;
    this.memtables = new MemtableSet(active, List.of());
    this.nextSequence = nextSequence;
    this.nextSegmentNumber = currentSegmentNumber + 1;
  }

  /**
   * Opens (or recovers) an engine rooted at {@code directory} with the default write-buffer size,
   * replaying any WAL segments.
   */
  public static Shale open(Path directory, Clock clock, Metrics metrics) throws IOException {
    return open(directory, clock, metrics, DEFAULT_WRITE_BUFFER_SIZE_BYTES);
  }

  /**
   * Opens (or recovers) an engine, switching the active memtable once it reaches {@code
   * writeBufferSizeBytes}. Recovery replays every present segment in order into one memtable; the
   * runtime active/immutable split is rebuilt as writes cross the threshold again.
   */
  public static Shale open(Path directory, Clock clock, Metrics metrics, long writeBufferSizeBytes)
      throws IOException {
    if (writeBufferSizeBytes <= 0) {
      throw new IllegalArgumentException("writeBufferSizeBytes must be positive");
    }
    Files.createDirectories(directory);
    KeyComparator comparator = BytewiseComparator.INSTANCE;
    Memtable active = new SkiplistMemtable(comparator, SKIPLIST_SEED);
    List<Path> segments = segmentsInOrder(directory);

    long maxSequence = 0;
    for (Path segment : segments) {
      for (byte[] payload : WalReader.readAll(segment, RecoveryPolicy.TRUNCATE_TAIL)) {
        WalRecordCodec.Decoded decoded = WalRecordCodec.decode(payload);
        active.add(decoded.internalKey().encode(), decoded.value());
        maxSequence = Math.max(maxSequence, decoded.internalKey().sequenceNumber());
      }
    }

    int currentNumber = segments.isEmpty() ? 1 : numberOf(segments.getLast()) + 1;
    WalWriter wal = WalWriter.open(directory.resolve(segmentName(currentNumber)), clock, metrics);
    return new Shale(
        directory,
        clock,
        metrics,
        writeBufferSizeBytes,
        comparator,
        wal,
        active,
        maxSequence + 1,
        currentNumber);
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
      Memtable active = memtables.active();
      active.add(key.encode(), value);
      metrics.gauge("memtable.size.bytes", active.sizeBytes());
      metrics.gauge("memtable.immutable.count", memtables.immutablesNewestFirst().size());
      if (active.sizeBytes() >= writeBufferSizeBytes) {
        switchMemtable();
      }
    } catch (IOException e) {
      throw new StorageException("WAL append failed", e);
    }
  }

  /**
   * Freezes the full active memtable into the immutable list and starts a fresh one on a new WAL
   * segment. @GuardedBy("writeLock"), reached only from {@link #append}. The old segment stays on
   * disk — fully durable — for recovery until its memtable is flushed to an SSTable (M3).
   */
  private void switchMemtable() throws IOException {
    wal.close();
    WalWriter rolled =
        WalWriter.open(directory.resolve(segmentName(nextSegmentNumber++)), clock, metrics);
    Memtable newActive = new SkiplistMemtable(userComparator, SKIPLIST_SEED);

    MemtableSet current = memtables;
    List<Memtable> immutables = new ArrayList<>(current.immutablesNewestFirst().size() + 1);
    immutables.add(current.active()); // the memtable we just froze is now the newest immutable
    immutables.addAll(current.immutablesNewestFirst());

    wal = rolled;
    memtables = new MemtableSet(newActive, List.copyOf(immutables)); // publish atomically
    metrics.increment("memtable.switch.count", 1);
  }

  @Override
  public byte[] get(byte[] userKey) {
    if (userKey == null) {
      throw new IllegalArgumentException("userKey is null");
    }
    byte[] lookup = new InternalKey(userKey, InternalKey.MAX_SEQUENCE, ValueType.FOR_SEEK).encode();
    // Newest memtable first: the first one holding any version of the key holds the newest version,
    // because a switch happens at a sequence boundary (all of active's sequences post-date any
    // immutable's), so no lock and no cross-memtable reconciliation is needed for a point read.
    for (Memtable memtable : memtables.newestFirst()) {
      Memtable.Entry entry = memtable.ceiling(lookup);
      if (entry == null) {
        continue;
      }
      InternalKey found = InternalKey.decode(entry.internalKey());
      if (userComparator.compare(found.userKey(), userKey) != 0) {
        continue; // this memtable has no version of the key; an older one might
      }
      return found.valueType() == ValueType.PUT ? entry.value().clone() : null;
    }
    return null;
  }

  @Override
  public Cursor scan(byte[] fromInclusive, byte[] toExclusive) {
    MemtableSet snapshot = memtables; // one volatile read: a stable view for the whole scan
    List<Memtable.Entry> merged = new ArrayList<>();
    for (Memtable memtable : snapshot.newestFirst()) {
      merged.addAll(memtable.entries());
    }
    // Sort by internal key (user asc, sequence desc) so the newest version of each user key — from
    // whichever memtable — sorts first; the real streaming heap merge across SSTables is M4.
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
    }
  }

  private static void requireArgs(byte[] userKey, byte[] value, Durability durability) {
    if (userKey == null || value == null || durability == null) {
      throw new IllegalArgumentException("userKey, value, and durability must be non-null");
    }
  }

  private static List<Path> segmentsInOrder(Path directory) throws IOException {
    try (Stream<Path> entries = Files.list(directory)) {
      return entries
          .filter(path -> SEGMENT_NAME.matcher(path.getFileName().toString()).matches())
          .sorted(Comparator.comparing(path -> path.getFileName().toString()))
          .toList();
    }
  }

  private static int numberOf(Path segment) {
    return Integer.parseInt(segment.getFileName().toString().substring(0, 6));
  }

  private static String segmentName(int number) {
    return String.format("%06d.wal", number);
  }

  /**
   * The live memtable set: one mutable active memtable and zero or more immutable ones awaiting
   * flush, newest first. Immutable so publishing a new set is a single volatile write.
   *
   * @param active the memtable receiving writes
   * @param immutablesNewestFirst frozen memtables not yet flushed, newest first
   */
  private record MemtableSet(Memtable active, List<Memtable> immutablesNewestFirst) {

    /** The active memtable followed by the immutables, newest to oldest — the read order. */
    List<Memtable> newestFirst() {
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
