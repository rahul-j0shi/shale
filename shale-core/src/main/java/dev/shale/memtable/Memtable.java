package dev.shale.memtable;

import java.util.List;

/**
 * A mutable in-memory store of encoded internal keys, ordered newest-version-first
 * (InternalKeyComparator). At M1 this is a plain sorted map; M2 replaces the implementation with a
 * hand-written skiplist without changing this seam. MVCC lookup (newest visible version, tombstone
 * handling) is done by the caller on top of {@link #ceiling}.
 *
 * <p><b>Threading:</b> implementations are single-threaded unless stated; the engine serialises
 * access.
 */
public interface Memtable {

  /** Inserts an encoded internal key and its value (empty for a delete). */
  void add(byte[] internalKey, byte[] value);

  /** The smallest entry whose internal key is ≥ {@code internalKey}, or {@code null}. */
  Entry ceiling(byte[] internalKey);

  /** All entries in ascending internal-key order. */
  List<Entry> entries();

  /** Approximate retained size in bytes (keys + values), for the flush trigger (M3). */
  long sizeBytes();

  /**
   * One stored entry. The arrays are the memtable's own; callers must not mutate them.
   *
   * @param internalKey the encoded internal key
   * @param value the value bytes (empty for a delete)
   */
  record Entry(byte[] internalKey, byte[] value) {}
}
