package dev.shale.wal;

import dev.shale.internal.annotations.ThreadSafe;
import dev.shale.internal.blocklog.BlockLogFormat;

/** WAL on-disk constants (ADR-0007, {@code format.md}). */
@ThreadSafe
final class WalFormat {

  /** {@code fixed64LE} at offset 0 of every segment: "ShaleWAL". */
  static final long MAGIC = 0x5368616C6557414CL;

  static final int FORMAT_VERSION = 1;

  /** The framing shared with the manifest; only the magic and version differ. */
  static final BlockLogFormat BLOCK_LOG = new BlockLogFormat(MAGIC, FORMAT_VERSION, "WAL");

  /** magic(8) + version(4) + reserved(4). */
  static final int FILE_HEADER_SIZE = BlockLogFormat.FILE_HEADER_SIZE;

  static final int BLOCK_SIZE = BlockLogFormat.BLOCK_SIZE;

  /** crc32c(4) + length(2) + type(1). */
  static final int FRAGMENT_HEADER_SIZE = BlockLogFormat.FRAGMENT_HEADER_SIZE;

  private WalFormat() {}
}
