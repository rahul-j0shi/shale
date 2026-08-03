package dev.shale.sstable;

import dev.shale.internal.annotations.ThreadSafe;

/** SSTable on-disk constants (ADR-0010, {@code format.md}). */
@ThreadSafe
final class SSTableFormat {

  /** {@code fixed64LE} at bytes [44, 52) of the footer: "ShaleSST". */
  static final long MAGIC = 0x5368616C65535354L;

  static final int FORMAT_VERSION = 1;

  /** metaindex+index handles padded to 40, then version(4), then magic(8). */
  static final int FOOTER_SIZE = 52;

  /** type(1) + crc32c(4) after every block's content. */
  static final int BLOCK_TRAILER_SIZE = 5;

  /** The only block type at v1: no compression. */
  static final byte BLOCK_TYPE_NONE = 0x00;

  /** Full key stored every this-many entries within a block. */
  static final int RESTART_INTERVAL = 16;

  /** Target uncompressed data-block size before a new block is started. */
  static final int BLOCK_SIZE_BYTES = 4096;

  private SSTableFormat() {}
}
