/**
 * The block-structured log that both the write-ahead log and the manifest are written in
 * (ADR-0007). A file is a 16-byte header followed by fixed-size blocks; a logical record is carried
 * by one {@code FULL} fragment or by {@code FIRST ‖ MIDDLE* ‖ LAST} when it spans blocks, each
 * fragment checksummed with CRC32C.
 *
 * <p>The framing knows nothing about what a record <em>means</em>. The WAL's payload is one
 * mutation and the manifest's is one version edit; both get the same fragmentation, the same
 * corruption detection, and the same rule for a torn record at the tail — which is the point of
 * having one implementation rather than two (ADR-0012).
 *
 * <p>A file distinguishes itself by its {@link dev.shale.internal.blocklog.BlockLogFormat}: its
 * magic and format version, and the name used in corruption messages.
 *
 * <p><b>Threading:</b> {@code BlockLogWriter} is single-threaded and owned by whoever appends;
 * {@code BlockLogReader} is stateless.
 *
 * <p><b>Entry point:</b> {@link dev.shale.internal.blocklog.BlockLogWriter}.
 */
package dev.shale.internal.blocklog;
