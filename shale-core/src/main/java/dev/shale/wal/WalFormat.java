package dev.shale.wal;

import dev.shale.internal.annotations.ThreadSafe;
import dev.shale.internal.log.LogFormat;
import java.util.Set;

/**
 * WAL on-disk constants ({@code format.md}). The blocks and fragments are the shared log framing
 * ({@link LogFormat}); the WAL owns its magic and which versions it writes and reads.
 */
@ThreadSafe
final class WalFormat {

  /** {@code fixed64LE} at offset 0 of every segment: "ShaleWAL". */
  static final long MAGIC = 0x5368616C6557414CL;

  /** The version this build writes: the log framing version (internal/log/format.md). */
  static final int FORMAT_VERSION = LogFormat.VERSION_1;

  /** Every version this build reads. */
  static final Set<Integer> READABLE_VERSIONS = Set.of(LogFormat.VERSION_1);

  static final int FILE_HEADER_SIZE = LogFormat.FILE_HEADER_SIZE;

  private WalFormat() {}
}
