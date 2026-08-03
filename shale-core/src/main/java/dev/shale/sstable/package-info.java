/**
 * The SSTable: an immutable, sorted, on-disk key/value table — a LevelDB block table (ADR-0010,
 * {@code format.md}). Prefix-compressed data blocks with restart points and a per-block CRC32C, a
 * last-key index block, an (M7-ready) metaindex block, and a versioned footer. An immutable
 * memtable is flushed here; reads consult these tables after the memtable set.
 *
 * <p><b>Threading:</b> {@code SSTableWriter} is single-threaded (owned by the flushing thread);
 * {@code SSTableReader} is thread-safe and reference-counted ({@code retain}/{@code release}, N6).
 * <b>Entry points:</b> {@code SSTableWriter}, {@code SSTableReader}. Byte layout: {@code
 * format.md}.
 */
package dev.shale.sstable;
