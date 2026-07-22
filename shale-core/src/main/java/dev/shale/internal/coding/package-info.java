/**
 * Binary integer coding shared by every on-disk and internal structure. Little-endian fixed widths
 * now; LevelDB-style varints arrive with the WAL (M1).
 *
 * <p><b>Threading:</b> stateless utilities. <b>Entry point:</b> {@link
 * dev.shale.internal.coding.LittleEndian}.
 */
package dev.shale.internal.coding;
