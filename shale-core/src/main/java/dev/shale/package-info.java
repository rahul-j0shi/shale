/**
 * The public API of {@code shale-core}: the {@link dev.shale.StorageBackend} SPI, the {@link
 * dev.shale.KeyComparator} it orders by, {@link dev.shale.Durability}, and the {@link
 * dev.shale.ShaleException} hierarchy. Everything not under {@code dev.shale.internal} is public
 * API — signature changes here require an ADR (naming.md §3).
 *
 * <p><b>Threading:</b> per type. <b>Entry point:</b> {@link dev.shale.StorageBackend}.
 */
package dev.shale;
