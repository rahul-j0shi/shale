package dev.shale.sstable;

import dev.shale.internal.annotations.Immutable;
import dev.shale.internal.coding.Varints;

/**
 * A locator for a block within an SSTable file: the byte {@code offset} of the block's content and
 * its {@code size} (content length, excluding the 5-byte block trailer). Encoded as two varint64s
 * (format.md §4), it appears in index entries and in the footer.
 *
 * <p><b>Threading:</b> immutable value.
 *
 * @param offsetBytes byte offset of the block's content in the file
 * @param sizeBytes block content length, excluding the block trailer
 * @see <a href="https://github.com/google/leveldb/blob/main/table/format.h">LevelDB format.h</a>
 */
@Immutable
public record BlockHandle(long offsetBytes, long sizeBytes) {

  /** Bytes this handle occupies when encoded. */
  public int encodedLength() {
    return Varints.size(offsetBytes) + Varints.size(sizeBytes);
  }

  /**
   * Writes {@code offset ‖ size} at {@code offset} in {@code destination}; returns the next offset.
   */
  public int encodeTo(byte[] destination, int offset) {
    int next = Varints.put(destination, offset, offsetBytes);
    return Varints.put(destination, next, sizeBytes);
  }

  /** Decodes a handle starting at {@code offset} in {@code source}. */
  public static Decoded decode(byte[] source, int offset) {
    Varints.Decoded blockOffset = Varints.get(source, offset);
    Varints.Decoded blockSize = Varints.get(source, blockOffset.nextOffset());
    BlockHandle handle = new BlockHandle(blockOffset.value(), blockSize.value());
    return new Decoded(handle, blockSize.nextOffset());
  }

  /**
   * A decoded handle and the offset just past it.
   *
   * @param handle the decoded block handle
   * @param nextOffset the offset immediately after the encoded handle
   */
  public record Decoded(BlockHandle handle, int nextOffset) {}
}
