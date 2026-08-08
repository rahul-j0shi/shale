package dev.shale.iterator;

import dev.shale.internal.key.InternalKey;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Test helper: collects an {@link InternalIterator} into a list, and describes internal keys
 * readably.
 *
 * <p>It exists because M4 removed the materialising {@code entries()} accessors from {@code
 * Memtable} and {@code SSTableReader} (ADR-0011) — the production code has no reason to build such
 * a list, but assertions are far clearer against one than against a hand-rolled advance loop. Note
 * that this deliberately lives in the test tree: making it production API would hand a future
 * milestone the unbounded read the ADR set out to remove.
 */
public final class Drain {

  private Drain() {}

  /**
   * One collected entry, with copies — the iterator's own arrays die at the next {@code next()}.
   *
   * @param internalKey the encoded internal key
   * @param value the value bytes (empty for a tombstone)
   */
  public record Entry(byte[] internalKey, byte[] value) {}

  /** Every entry from the start, in iteration order. Does not close the iterator. */
  public static List<Entry> of(InternalIterator iterator) {
    List<Entry> out = new ArrayList<>();
    for (iterator.seekToFirst(); iterator.valid(); iterator.next()) {
      out.add(new Entry(iterator.internalKey().clone(), iterator.value().clone()));
    }
    return out;
  }

  /** Every entry from {@code target} onward, in iteration order. Does not close the iterator. */
  public static List<Entry> from(InternalIterator iterator, byte[] target) {
    List<Entry> out = new ArrayList<>();
    for (iterator.seek(target); iterator.valid(); iterator.next()) {
      out.add(new Entry(iterator.internalKey().clone(), iterator.value().clone()));
    }
    return out;
  }

  /** {@code userKey#sequence} — the form the memtable and SSTable suites already assert against. */
  public static String describe(byte[] internalKey) {
    InternalKey key = InternalKey.decode(internalKey);
    return new String(key.userKey(), StandardCharsets.US_ASCII) + "#" + key.sequenceNumber();
  }

  /** The {@link #describe} form of every entry, in iteration order. */
  public static List<String> describeAll(InternalIterator iterator) {
    return of(iterator).stream().map(entry -> describe(entry.internalKey())).toList();
  }
}
