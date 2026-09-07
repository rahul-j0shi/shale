/**
 * Binary integer coding shared by every on-disk and internal structure: little-endian fixed widths
 * (ADR-0005) and LevelDB-style varints.
 *
 * <p><b>Threading:</b> stateless utilities. <b>Entry point:</b> {@link
 * dev.shale.internal.coding.LittleEndian}.
 */
package dev.shale.internal.coding;
