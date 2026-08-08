/**
 * The SSTable: an immutable, sorted, on-disk key/value table — a LevelDB block table (ADR-0010,
 * {@code format.md}). Prefix-compressed data blocks with restart points and a per-block CRC32C, a
 * last-key index block, an (M7-ready) metaindex block, and a versioned footer. An immutable
 * memtable is flushed here. Two read shapes: {@code SSTableReader.ceiling} answers the engine's
 * point lookup in one descent, and {@code SSTableReader.iterator} returns the two-level index →
 * data-block cursor that feeds the merge spanning every source (ADR-0011). Data blocks are opened
 * only as a read reaches them, so iterating a table costs one block rather than the table.
 *
 * <p><b>Threading:</b> {@code SSTableWriter} is single-threaded (owned by the flushing thread);
 * {@code SSTableReader} is thread-safe and reference-counted ({@code retain}/{@code release}, N6).
 * <b>Entry points:</b> {@code SSTableWriter}, {@code SSTableReader}. Byte layout: {@code
 * format.md}.
 */
package dev.shale.sstable;
