package dev.shale.wal;

import dev.shale.internal.annotations.ThreadSafe;

/** WAL on-disk constants (ADR-0007, {@code format.md}). */
@ThreadSafe
final class WalFormat {

  /** {@code fixed64LE} at offset 0 of every segment: "ShaleWAL". */
  static final long MAGIC = 0x5368616C6557414CL;

  static final int FORMAT_VERSION = 1;

  /** magic(8) + version(4) + reserved(4). */
  static final int FILE_HEADER_SIZE = 16;

  static final int BLOCK_SIZE = 32768;

  /** crc32c(4) + length(2) + type(1). */
  static final int FRAGMENT_HEADER_SIZE = 7;

  private WalFormat() {}
}
