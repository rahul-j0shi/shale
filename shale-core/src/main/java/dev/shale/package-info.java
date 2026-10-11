/**
 * The public API of {@code shale-core}: the {@link dev.shale.StorageBackend} SPI, the {@link
 * dev.shale.KeyComparator} it orders by, {@link dev.shale.Durability}, and the {@link
 * dev.shale.ShaleException} hierarchy. Everything not under {@code dev.shale.internal} is public
 * API — signature changes here require an ADR (naming.md §3).
 *
 * <p><b>Reads come in two shapes (ADR-0011).</b> {@link dev.shale.StorageBackend#get} probes each
 * source newest-first and stops at the first holding any version of the key. {@link
 * dev.shale.StorageBackend#scan} returns a {@link dev.shale.Cursor} streaming a heap merge across
 * every source. <b>A cursor must be closed:</b> it pins the {@code Version} it reads for its
 * lifetime (N6), so leaking one keeps its tables open, and on disk.
 *
 * <p><b>Opening and options (ADR-0012).</b> {@link dev.shale.Shale#open(java.nio.file.Path,
 * dev.shale.ShaleOptions, dev.shale.Clock, dev.shale.Metrics)} takes {@link dev.shale.ShaleOptions}
 * — the write buffer, the manifest rollover size, and the {@link dev.shale.env.Env} every file
 * operation goes through. The manifest, not the directory listing, says which tables are live.
 *
 * <p><b>Threading:</b> per type. The engine ({@link dev.shale.Shale}) serialises writers on a
 * single private {@code writeLock} — which guards the WAL, the sequence counter, flush, and
 * publication of the read view (active memtable + immutable memtables) — while readers are
 * lock-free over that {@code volatile} view and retain the current {@code Version} for its tables.
 * The {@code VersionSet}'s {@code versionLock} ranks below {@code writeLock}
 * (concurrency-and-resources.md §2). <b>Entry point:</b> {@link dev.shale.StorageBackend}.
 */
package dev.shale;
