package dev.shale.internal.log;

import dev.shale.internal.annotations.ThreadSafe;

/**
 * The constants of the log framing ({@code format.md} beside this source): a 16-byte file header,
 * then 32 KiB blocks of fragments. The magic and version in the file header belong to the log that
 * uses the framing; the version also selects the fragment header layout.
 */
@ThreadSafe
public final class LogFormat {

  /** magic(8) + version(4) + reserved(4). */
  public static final int FILE_HEADER_SIZE = 16;

  public static final int BLOCK_SIZE = 32_768;

  /** {@code payloadCrc(4) ‖ length(2) ‖ type(1)}: ADR-0007. */
  public static final int VERSION_1 = 1;

  private LogFormat() {}

  /** The fragment header size of a framing {@code version}. */
  public static int fragmentHeaderSize(int version) {
    return switch (version) {
      case VERSION_1 -> 7;
      default -> throw new IllegalArgumentException("unknown log framing version " + version);
    };
  }
}
