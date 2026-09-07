package dev.shale.manifest;

import dev.shale.internal.annotations.ThreadSafe;
import dev.shale.internal.blocklog.BlockLogFormat;

/** Manifest on-disk constants (ADR-0012, {@code format.md}). */
@ThreadSafe
final class ManifestFormat {

  /** {@code fixed64LE} at offset 0 of every manifest: "ShaleMAN". */
  static final long MAGIC = 0x5368616C654D414EL;

  static final int FORMAT_VERSION = 1;

  /**
   * The same framing as the WAL, distinguished only by the magic — so a manifest handed to the WAL
   * reader, or the reverse, fails on the header rather than misparsing a record.
   */
  static final BlockLogFormat BLOCK_LOG = new BlockLogFormat(MAGIC, FORMAT_VERSION, "manifest");

  private ManifestFormat() {}
}
