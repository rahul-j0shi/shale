package dev.shale.sstable;

import dev.shale.internal.annotations.NotThreadSafe;
import dev.shale.internal.coding.Crc32c;
import dev.shale.internal.coding.LittleEndian;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Writes one SSTable (format.md §1): buffers entries into a data block until it reaches the target
 * block size, flushes it with a `type ‖ crc32c` trailer, and records its last key → {@link
 * BlockHandle} in the index. {@link #finish} writes the (empty) metaindex block, the index block,
 * and the footer, then `force()`s the whole table to disk. Keys must be added in ascending
 * internal-key order (the memtable already yields them so).
 *
 * <p><b>Threading:</b> not thread-safe; owned by the flushing thread. <b>Ownership:</b> holds a
 * {@link FileChannel} closed by {@link #close()}.
 *
 * @see <a href="https://github.com/google/leveldb/blob/main/table/table_builder.cc">LevelDB
 *     table_builder.cc</a>
 */
@NotThreadSafe
public final class SSTableWriter implements AutoCloseable {

  private final FileChannel channel;
  private final BlockBuilder indexBlock = new BlockBuilder(SSTableFormat.RESTART_INTERVAL);
  private BlockBuilder dataBlock = new BlockBuilder(SSTableFormat.RESTART_INTERVAL);
  private long fileOffset;
  private byte[] lastKey = new byte[0];

  private SSTableWriter(FileChannel channel) {
    this.channel = channel;
  }

  /** Creates a new table file at {@code path} (which must not exist). */
  public static SSTableWriter open(Path path) throws IOException {
    FileChannel channel =
        FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    return new SSTableWriter(channel);
  }

  /** Appends {@code internalKey → value}; {@code internalKey} must be ≥ every key added so far. */
  public void add(byte[] internalKey, byte[] value) throws IOException {
    dataBlock.add(internalKey, value);
    lastKey = internalKey;
    if (dataBlock.currentSizeBytes() >= SSTableFormat.BLOCK_SIZE_BYTES) {
      flushDataBlock();
    }
  }

  /**
   * Flushes the final data block, then writes the metaindex, index, and footer, and forces to disk.
   */
  public void finish() throws IOException {
    if (!dataBlock.isEmpty()) {
      flushDataBlock();
    }
    BlockHandle metaindexHandle =
        writeBlock(new BlockBuilder(SSTableFormat.RESTART_INTERVAL).finish()); // empty at v1
    BlockHandle indexHandle = writeBlock(indexBlock.finish());
    writeFully(ByteBuffer.wrap(new Footer(metaindexHandle, indexHandle).encode()));
    // DURABILITY: after force() returns the whole table survives power loss; the engine deletes the
    // covering WAL segment only after this returns (D3, ADR-0010).
    channel.force(true);
  }

  private void flushDataBlock() throws IOException {
    BlockHandle handle = writeBlock(dataBlock.finish());
    indexBlock.add(lastKey, encodeHandle(handle)); // index key = the block's last key
    dataBlock = new BlockBuilder(SSTableFormat.RESTART_INTERVAL);
  }

  /** Writes {@code content} plus its `type ‖ crc32c` trailer; returns the block's handle. */
  private BlockHandle writeBlock(byte[] content) throws IOException {
    byte[] frame = new byte[content.length + SSTableFormat.BLOCK_TRAILER_SIZE];
    System.arraycopy(content, 0, frame, 0, content.length);
    frame[content.length] = SSTableFormat.BLOCK_TYPE_NONE;
    int crc = Crc32c.of(frame, 0, content.length + 1); // over content ‖ type
    LittleEndian.putFixed32(frame, content.length + 1, crc);

    BlockHandle handle = new BlockHandle(fileOffset, content.length);
    writeFully(ByteBuffer.wrap(frame));
    fileOffset += frame.length;
    return handle;
  }

  private static byte[] encodeHandle(BlockHandle handle) {
    byte[] out = new byte[handle.encodedLength()];
    handle.encodeTo(out, 0);
    return out;
  }

  private void writeFully(ByteBuffer buffer) throws IOException {
    while (buffer.hasRemaining()) {
      channel.write(buffer);
    }
  }

  @Override
  public void close() throws IOException {
    channel.close();
  }
}
