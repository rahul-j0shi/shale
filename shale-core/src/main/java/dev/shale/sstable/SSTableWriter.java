package dev.shale.sstable;

import dev.shale.env.Env;
import dev.shale.env.WritableFile;
import dev.shale.internal.annotations.NotThreadSafe;
import dev.shale.internal.coding.Crc32c;
import dev.shale.internal.coding.LittleEndian;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Writes one SSTable (format.md §1): buffers entries into a data block until it reaches the target
 * block size, flushes it with a `type ‖ crc32c` trailer, and records its last key → {@link
 * BlockHandle} in the index. {@link #finish} writes the (empty) metaindex block, the index block,
 * and the footer, then syncs the whole table to disk. Keys must be added in ascending internal-key
 * order (the memtable already yields them so).
 *
 * <p><b>Threading:</b> not thread-safe; owned by the flushing thread. <b>Ownership:</b> holds a
 * {@link WritableFile} closed by {@link #close()}.
 *
 * @see <a href="https://github.com/google/leveldb/blob/main/table/table_builder.cc">LevelDB
 *     table_builder.cc</a>
 */
@NotThreadSafe
public final class SSTableWriter implements AutoCloseable {

  private final WritableFile file;
  private final BlockBuilder indexBlock = new BlockBuilder(SSTableFormat.RESTART_INTERVAL);
  private BlockBuilder dataBlock = new BlockBuilder(SSTableFormat.RESTART_INTERVAL);
  private long fileOffset;
  private byte[] lastKey = new byte[0];
  private byte[] firstKey;
  private long smallestSequence = Long.MAX_VALUE;
  private long largestSequence = -1;

  private SSTableWriter(WritableFile file) {
    this.file = file;
  }

  /** Creates a new table file at {@code path} (which must not exist). */
  public static SSTableWriter open(Path path) throws IOException {
    return open(Env.DEFAULT, path);
  }

  /** As {@link #open(Path)}, through {@code env}. */
  public static SSTableWriter open(Env env, Path path) throws IOException {
    return new SSTableWriter(env.newWritableFile(path));
  }

  /** Appends {@code internalKey → value}; {@code internalKey} must be ≥ every key added so far. */
  public void add(byte[] internalKey, byte[] value) throws IOException {
    dataBlock.add(internalKey, value);
    if (firstKey == null) {
      firstKey = internalKey.clone();
    }
    lastKey = internalKey;
    long sequence = sequenceOf(internalKey);
    smallestSequence = Math.min(smallestSequence, sequence);
    largestSequence = Math.max(largestSequence, sequence);
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
    file.append(new Footer(metaindexHandle, indexHandle).encode());
    // DURABILITY: after sync() returns the whole table survives power loss; the engine deletes the
    // covering WAL segment only after this returns (D3, ADR-0010).
    file.sync();
  }

  /** The first internal key added: the table's smallest. Requires at least one {@link #add}. */
  public byte[] smallestKey() {
    requireEntries();
    return firstKey.clone();
  }

  /** The last internal key added: the table's largest. */
  public byte[] largestKey() {
    requireEntries();
    return lastKey.clone();
  }

  /**
   * The smallest sequence number added. Keys ascend by user key, not by sequence, so this is
   * tracked across every key rather than read from the first.
   */
  public long smallestSequence() {
    requireEntries();
    return smallestSequence;
  }

  /** The largest sequence number added. */
  public long largestSequence() {
    requireEntries();
    return largestSequence;
  }

  /** The bytes written so far; after {@link #finish}, the table's size. */
  public long fileSizeBytes() {
    return file.size();
  }

  private void requireEntries() {
    if (firstKey == null) {
      throw new IllegalStateException("no entry has been added");
    }
  }

  /** The sequence number in an internal key's trailer: {@code fixed64LE(sequence << 8 | type)}. */
  private static long sequenceOf(byte[] internalKey) {
    if (internalKey.length < 8) {
      throw new IllegalArgumentException("not an internal key: shorter than its 8-byte trailer");
    }
    return LittleEndian.getFixed64(internalKey, internalKey.length - 8) >>> 8;
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
    file.append(frame);
    fileOffset += frame.length;
    return handle;
  }

  private static byte[] encodeHandle(BlockHandle handle) {
    byte[] out = new byte[handle.encodedLength()];
    handle.encodeTo(out, 0);
    return out;
  }

  @Override
  public void close() throws IOException {
    file.close();
  }
}
