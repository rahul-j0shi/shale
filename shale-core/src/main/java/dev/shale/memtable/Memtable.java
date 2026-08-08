package dev.shale.memtable;

import dev.shale.iterator.InternalIterator;

/**
 * A mutable in-memory store of encoded internal keys, ordered newest-version-first
 * (InternalKeyComparator). At M1 this is a plain sorted map; M2 replaces the implementation with a
 * hand-written skiplist without changing this seam. MVCC lookup (newest visible version, tombstone
 * handling) is done by the caller on top of {@link #ceiling}.
 *
 * <p>Two read shapes, deliberately: {@link #ceiling} answers a point lookup in one probe, and
 * {@link #iterator} feeds the merge that spans every source (ADR-0011). They must agree about where
 * a target resolves — if they ever disagree, {@code get} and {@code scan} answer differently for
 * the same key.
 *
 * <p><b>Threading:</b> implementations are single-threaded unless stated; the engine serialises
 * access.
 */
public interface Memtable {

  /** Inserts an encoded internal key and its value (empty for a delete). */
  void add(byte[] internalKey, byte[] value);

  /** The smallest entry whose internal key is ≥ {@code internalKey}, or {@code null}. */
  Entry ceiling(byte[] internalKey);

  /**
   * A fresh, unpositioned cursor over every entry in ascending internal-key order.
   *
   * <p>Replaced the materialising {@code entries()} at M4: a scan that copies the whole memtable
   * before it can return its first key costs the buffer rather than the result (ADR-0011).
   */
  InternalIterator iterator();

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
