/**
 * The public API of {@code shale-core}: the {@link dev.shale.StorageBackend} SPI, the {@link
 * dev.shale.KeyComparator} it orders by, {@link dev.shale.Durability}, and the {@link
 * dev.shale.ShaleException} hierarchy. Everything not under {@code dev.shale.internal} is public
 * API — signature changes here require an ADR (naming.md §3).
 *
 * <p><b>Threading:</b> per type. The engine ({@link dev.shale.Shale}) serialises writers on a
 * single private {@code writeLock} — which guards the WAL, the sequence and file-number counters,
 * flush, and publication of the read view (active memtable + immutable memtables + SSTables) —
 * while readers are lock-free over that {@code volatile} view (concurrency-and-resources.md §2). As
 * deeper locks arrive (a {@code versionLock} at M5, etc.) they are acquired in the order documented
 * there. <b>Entry point:</b> {@link dev.shale.StorageBackend}.
 */
package dev.shale;
