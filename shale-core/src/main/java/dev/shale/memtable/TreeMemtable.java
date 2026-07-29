package dev.shale.memtable;

import dev.shale.ByteRange;
import dev.shale.KeyComparator;
import dev.shale.internal.annotations.NotThreadSafe;
import dev.shale.internal.key.InternalKeyComparator;
import java.util.ArrayList;
import java.util.List;
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
  public List<Entry> entries() {
    List<Entry> out = new ArrayList<>(store.size());
    for (Map.Entry<byte[], byte[]> entry : store.entrySet()) {
      out.add(new Entry(entry.getKey(), entry.getValue()));
    }
    return out;
  }

  @Override
  public long sizeBytes() {
    return sizeBytes;
  }
}
