package dev.shale.internal.blocklog;

import dev.shale.internal.annotations.Immutable;

/**
 * What distinguishes one block log from another: its magic, its format version, and the name used
 * in corruption messages. Everything else about the framing — block size, header sizes, checksum —
 * is shared, and lives here as constants.
 *
 * @param magic the {@code fixed64LE} at offset 0, so a file opened by the wrong reader fails on the
 *     magic rather than on a misparsed record
 * @param formatVersion the {@code fixed32LE} at offset 8
 * @param name how this log is described in a {@link dev.shale.CorruptionException} — "WAL",
 *     "manifest"
 */
@Immutable
public record BlockLogFormat(long magic, int formatVersion, String name) {

  /** magic(8) + version(4) + reserved(4). */
  public static final int FILE_HEADER_SIZE = 16;

  /** Fixed block size; a record is fragmented to fit (ADR-0007). */
  public static final int BLOCK_SIZE = 32768;

  /** crc32c(4) + length(2) + type(1). */
  public static final int FRAGMENT_HEADER_SIZE = 7;
}
