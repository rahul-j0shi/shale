/**
 * The memtable: the mutable in-memory buffer that receives writes after the WAL. The engine's
 * memtable is {@link dev.shale.memtable.SkiplistMemtable}, a hand-written skiplist behind the
 * {@link dev.shale.memtable.Memtable} seam; {@link dev.shale.memtable.TreeMemtable} is retained as
 * the differential-test oracle.
 *
 * <p><b>Threading:</b> {@code SkiplistMemtable} is {@code @ThreadSafe} under a single-writer /
 * lock-free-reader contract (ADR-0009) — the engine serialises inserts on its write lock, while any
 * number of readers traverse without a lock. {@code TreeMemtable} is single-threaded. <b>Entry
 * point:</b> {@link dev.shale.memtable.Memtable}.
 */
package dev.shale.memtable;
