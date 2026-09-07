/**
 * The record of which files the database consists of (ADR-0012). A manifest is an append-only log
 * of {@link dev.shale.manifest.VersionEdit} records; the live file set is the fold of every edit in
 * the file, and a {@code CURRENT} file names the manifest currently in force.
 *
 * <p>The framing is the WAL's block log with a different magic — see {@code
 * dev.shale.manifest.format.md} for the byte layout and {@code dev.shale.wal.format.md} §2-3 for
 * the framing it reuses.
 *
 * <p><b>Threading:</b> the codec types are stateless and safe for any thread; the reader and writer
 * are single-threaded and owned by the engine's version-install path.
 *
 * <p><b>Entry point:</b> {@link dev.shale.manifest.VersionEdit}.
 */
package dev.shale.manifest;
