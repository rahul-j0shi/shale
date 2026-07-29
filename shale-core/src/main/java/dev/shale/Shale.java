package dev.shale;

import dev.shale.internal.annotations.ThreadSafe;
import dev.shale.internal.key.InternalKey;
import dev.shale.internal.key.ValueType;
import dev.shale.memtable.Memtable;
import dev.shale.memtable.TreeMemtable;
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
 * The single-node LSM engine (M1 slice): every mutation is logged to the WAL before it is applied
 * to an in-memory {@link Memtable}, and {@link #open} replays the log to recover. This is the
 * durable {@link StorageBackend}; SSTables, flush, and compaction arrive at M3+.
 *
 * <p><b>Threading:</b> thread-safe; all operations serialise on one monitor. Reads block writes at
 * M1 — the immutable-memtable snapshot that removes that comes with M2. <b>Ownership:</b> owns a
 * {@link WalWriter} closed by {@link #close()}.
 *
 * @see "Petrov, Database Internals, ch. 5 — write-ahead logging and recovery"
 */
@ThreadSafe
public final class Shale implements StorageBackend {

  private static final Pattern SEGMENT_NAME = Pattern.compile("\\d{6}\\.wal");
  private static final byte[] NO_VALUE = new byte[0];

  private final Object lock = new Object();
  private final KeyComparator userComparator;
  private final WalWriter wal;
  private final Memtable memtable;
  private long nextSequence;

  private Shale(KeyComparator userComparator, WalWriter wal, Memtable memtable, long nextSequence) {
    this.userComparator = userComparator;
    this.wal = wal;
    this.memtable = memtable;
    this.nextSequence = nextSequence;
  }

  /** Opens (or recovers) an engine rooted at {@code directory}, replaying any WAL segments. */
  public static Shale open(Path directory, Clock clock, Metrics metrics) throws IOException {
    Files.createDirectories(directory);
    KeyComparator comparator = BytewiseComparator.INSTANCE;
    Memtable memtable = new TreeMemtable(comparator);
    List<Path> segments = segmentsInOrder(directory);

    long maxSequence = 0;
    for (Path segment : segments) {
      for (byte[] payload : WalReader.readAll(segment, RecoveryPolicy.TRUNCATE_TAIL)) {
        WalRecordCodec.Decoded decoded = WalRecordCodec.decode(payload);
        memtable.add(decoded.internalKey().encode(), decoded.value());
        maxSequence = Math.max(maxSequence, decoded.internalKey().sequenceNumber());
      }
    }

    int nextNumber = segments.isEmpty() ? 1 : numberOf(segments.getLast()) + 1;
    WalWriter wal = WalWriter.open(directory.resolve(segmentName(nextNumber)), clock, metrics);
    return new Shale(comparator, wal, memtable, maxSequence + 1);
  }

  @Override
  public void put(byte[] userKey, byte[] value, Durability durability) {
    requireArgs(userKey, value, durability);
    synchronized (lock) {
      append(new InternalKey(userKey, nextSequence++, ValueType.PUT), value, durability);
    }
  }

  @Override
  public void delete(byte[] userKey, Durability durability) {
    if (userKey == null || durability == null) {
      throw new IllegalArgumentException("userKey and durability must be non-null");
    }
    synchronized (lock) {
      append(new InternalKey(userKey, nextSequence++, ValueType.DELETE), NO_VALUE, durability);
    }
  }

  private void append(InternalKey key, byte[] value, Durability durability) {
    try {
      wal.append(WalRecordCodec.encode(key, value), durability); // D3: WAL durable before memtable
    } catch (IOException e) {
      throw new StorageException("WAL append failed", e);
    }
    memtable.add(key.encode(), value);
  }

  @Override
  public byte[] get(byte[] userKey) {
    if (userKey == null) {
      throw new IllegalArgumentException("userKey is null");
    }
    synchronized (lock) {
      byte[] lookup =
          new InternalKey(userKey, InternalKey.MAX_SEQUENCE, ValueType.FOR_SEEK).encode();
      Memtable.Entry entry = memtable.ceiling(lookup);
      if (entry == null) {
        return null;
      }
      InternalKey found = InternalKey.decode(entry.internalKey());
      if (userComparator.compare(found.userKey(), userKey) != 0) {
        return null; // no version of this user key
      }
      return found.valueType() == ValueType.PUT ? entry.value().clone() : null;
    }
  }

  @Override
  public Cursor scan(byte[] fromInclusive, byte[] toExclusive) {
    synchronized (lock) {
      List<byte[]> keys = new ArrayList<>();
      List<byte[]> values = new ArrayList<>();
      byte[] previousUserKey = null;
      for (Memtable.Entry entry : memtable.entries()) {
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
    synchronized (lock) {
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
