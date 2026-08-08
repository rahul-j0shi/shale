package dev.shale.memtable;

import dev.shale.ByteRange;
import dev.shale.KeyComparator;
import dev.shale.internal.annotations.NotThreadSafe;
import dev.shale.internal.key.InternalKeyComparator;
import dev.shale.iterator.InternalIterator;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * The M1 memtable: a {@link TreeMap} of encoded internal keys ordered by {@link
 * InternalKeyComparator}. Correctness-first; the concurrent skiplist arrives at M2.
 *
 * <p><b>Threading:</b> single-threaded; the engine owns and serialises it.
 */
@NotThreadSafe
public final class TreeMemtable implements Memtable {

  private final NavigableMap<byte[], byte[]> store;
  private long sizeBytes;

  public TreeMemtable(KeyComparator userComparator) {
    InternalKeyComparator ordering = new InternalKeyComparator(userComparator);
    this.store = new TreeMap<>((a, b) -> ordering.compare(ByteRange.of(a), ByteRange.of(b)));
  }

  @Override
  public void add(byte[] internalKey, byte[] value) {
    store.put(internalKey.clone(), value.clone());
    sizeBytes += (long) internalKey.length + value.length;
  }

  @Override
  public Entry ceiling(byte[] internalKey) {
    Map.Entry<byte[], byte[]> entry = store.ceilingEntry(internalKey);
    return entry == null ? null : new Entry(entry.getKey(), entry.getValue());
  }

  @Override
  public InternalIterator iterator() {
    return new TreeIterator();
  }

  @Override
  public long sizeBytes() {
    return sizeBytes;
  }

  /**
   * A cursor over the backing map's ordered entries. {@code seek} re-derives a tail view rather
   * than advancing the existing one, so seeking backwards is as valid as seeking forwards — the
   * skiplist's descent has the same property, and the merge relies on it when a scan's lower bound
   * lands behind where a previous seek left the cursor.
   */
  @NotThreadSafe
  private final class TreeIterator implements InternalIterator {

    /** Walks the current tail view. Null until positioned. Single-thread owned. */
    private java.util.Iterator<Map.Entry<byte[], byte[]>> cursor;

    /** The entry under the cursor, or null when unpositioned or exhausted. */
    private Map.Entry<byte[], byte[]> current;

    @Override
    public void seek(byte[] internalKey) {
      cursor = store.tailMap(internalKey, true).entrySet().iterator();
      advance();
    }

    @Override
    public void seekToFirst() {
      cursor = store.entrySet().iterator();
      advance();
    }

    @Override
    public boolean valid() {
      return current != null;
    }

    @Override
    public void next() {
      advance();
    }

    @Override
    public byte[] internalKey() {
      return current.getKey();
    }

    @Override
    public byte[] value() {
      return current.getValue();
    }

    @Override
    public void close() {
      cursor = null;
      current = null;
    }

    private void advance() {
      current = cursor != null && cursor.hasNext() ? cursor.next() : null;
    }
  }
}
