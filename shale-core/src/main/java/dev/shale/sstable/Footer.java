package dev.shale.sstable;

import dev.shale.CorruptionException;
import dev.shale.internal.annotations.Immutable;
import dev.shale.internal.coding.LittleEndian;

/**
 * The fixed 52-byte SSTable footer (format.md §6): the metaindex and index {@link BlockHandle}s,
 * zero-padded to 40 bytes, then {@code FORMAT_VERSION} and the magic. It is read from the end of
 * the file and is the entry point into the table.
 *
 * <p><b>Threading:</b> immutable value.
 *
 * @param metaindexHandle locator of the metaindex block (empty at v1)
 * @param indexHandle locator of the index block
 */
@Immutable
record Footer(BlockHandle metaindexHandle, BlockHandle indexHandle) {

  private static final int PADDED_HANDLES_SIZE = 40;
  private static final int VERSION_OFFSET = 40;
  private static final int MAGIC_OFFSET = 44;

  /** Encodes the footer into exactly {@link SSTableFormat#FOOTER_SIZE} bytes. */
  byte[] encode() {
    byte[] out = new byte[SSTableFormat.FOOTER_SIZE];
    int next = metaindexHandle.encodeTo(out, 0);
    next = indexHandle.encodeTo(out, next);
    // bytes [next, 40) stay zero (padding)
    LittleEndian.putFixed32(out, VERSION_OFFSET, SSTableFormat.FORMAT_VERSION);
    LittleEndian.putFixed64(out, MAGIC_OFFSET, SSTableFormat.MAGIC);
    return out;
  }

  /**
   * Decodes and validates a footer; a bad magic, version, or non-zero padding is corruption (N4).
   */
  static Footer decode(byte[] footer, long fileOffset) {
    if (footer.length != SSTableFormat.FOOTER_SIZE) {
      throw new CorruptionException(
          "footer wrong size", fileOffset, SSTableFormat.FOOTER_SIZE, footer.length);
    }
    long magic = LittleEndian.getFixed64(footer, MAGIC_OFFSET);
    if (magic != SSTableFormat.MAGIC) {
      throw new CorruptionException(
          "bad SSTable magic", fileOffset + MAGIC_OFFSET, SSTableFormat.MAGIC, magic);
    }
    int version = LittleEndian.getFixed32(footer, VERSION_OFFSET);
    if (version != SSTableFormat.FORMAT_VERSION) {
      throw new CorruptionException(
          "unsupported SSTable version",
          fileOffset + VERSION_OFFSET,
          SSTableFormat.FORMAT_VERSION,
          version);
    }
    BlockHandle.Decoded metaindex = BlockHandle.decode(footer, 0);
    BlockHandle.Decoded index = BlockHandle.decode(footer, metaindex.nextOffset());
    for (int i = index.nextOffset(); i < PADDED_HANDLES_SIZE; i++) {
      if (footer[i] != 0) {
        throw new CorruptionException("non-zero footer padding", fileOffset + i, 0, footer[i]);
      }
    }
    return new Footer(metaindex.handle(), index.handle());
  }
}
