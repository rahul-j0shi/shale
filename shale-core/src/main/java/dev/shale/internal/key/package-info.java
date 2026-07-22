/**
 * The internal-key encoding (ADR-0004): user key + packed (sequence, value type), and the
 * comparator that orders it newest-first. Referenced by the memtable, every SSTable, the WAL,
 * compaction, and MVCC — the single most load-bearing format in the engine.
 *
 * <p><b>Threading:</b> all types here are immutable value types / stateless comparators. <b>Entry
 * point:</b> {@link dev.shale.internal.key.InternalKey}.
 */
package dev.shale.internal.key;
