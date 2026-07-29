/**
 * The memtable: the mutable in-memory buffer that receives writes after the WAL. M1 provides a
 * {@link dev.shale.memtable.TreeMemtable}; M2 replaces it with a hand-written skiplist behind the
 * same {@link dev.shale.memtable.Memtable} seam.
 *
 * <p><b>Threading:</b> implementations are single-threaded; the engine serialises access. <b>Entry
 * point:</b> {@link dev.shale.memtable.Memtable}.
 */
package dev.shale.memtable;
