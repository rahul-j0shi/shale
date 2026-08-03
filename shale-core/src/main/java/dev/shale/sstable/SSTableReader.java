package dev.shale.sstable;

import dev.shale.CorruptionException;
import dev.shale.KeyComparator;
import dev.shale.StorageException;
import dev.shale.internal.annotations.Immutable;
import dev.shale.internal.annotations.ThreadSafe;
import dev.shale.internal.coding.Crc32c;
import dev.shale.internal.coding.LittleEndian;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Reads an SSTable back (format.md §1). {@link #open} verifies the footer and loads the index
 * block; {@link #ceiling} resolves a point lookup index → data block → restart search, and {@link
 * #entries} iterates the whole table in order. Data blocks are read from the file on demand and
 * their CRC verified before use (N4).
 *
 * <p><b>Threading:</b> thread-safe — the index block is immutable and data-block reads are
 * positional ({@code FileChannel.read(buffer, position)}), so concurrent readers do not interfere.
 * <b>Ownership:</b> reference-counted (N6): {@link #open} returns one reference; {@link #release}
 * drops one and closes the channel at zero. File deletion on the last release arrives with the
 * manifest (M5); at M3 nothing deletes a table.
 *
 * @see <a href="https://github.com/google/leveldb/blob/main/table/table.cc">LevelDB table.cc</a>
 */
@ThreadSafe
public final class SSTableReader implements AutoCloseable {

  private final Path path;
  private final FileChannel channel;
  private final long fileSizeBytes;
  private final KeyComparator comparator;
  private final Block indexBlock;
  private final AtomicInteger references = new AtomicInteger(1);

  private SSTableReader(
      Path path,
      FileChannel channel,
      long fileSizeBytes,
      KeyComparator comparator,
      Block indexBlock) {
    this.path = path;
    this.channel = channel;
    this.fileSizeBytes = fileSizeBytes;
    this.comparator = comparator;
    this.indexBlock = indexBlock;
  }

  /**
   * Opens {@code path}, verifying the footer and loading the index; the caller holds one reference.
   */
  public static SSTableReader open(Path path, KeyComparator comparator) throws IOException {
    FileChannel channel = FileChannel.open(path, StandardOpenOption.READ);
    boolean opened = false;
    try {
      long size = channel.size();
      if (size < SSTableFormat.FOOTER_SIZE) {
        throw new CorruptionException(
            "file smaller than a footer", 0, SSTableFormat.FOOTER_SIZE, size);
      }
      byte[] footerBytes =
          readAt(channel, size - SSTableFormat.FOOTER_SIZE, SSTableFormat.FOOTER_SIZE);
      Footer footer = Footer.decode(footerBytes, size - SSTableFormat.FOOTER_SIZE);
      Block indexBlock = new Block(readBlock(channel, size, footer.indexHandle()));
      SSTableReader reader = new SSTableReader(path, channel, size, comparator, indexBlock);
      opened = true;
      return reader;
    } finally {
      if (!opened) {
        channel.close(); // a bad footer/index must not leak the handle
      }
    }
  }

  /** The smallest stored entry whose internal key is ≥ {@code lookupKey}, or {@code null}. */
  public Entry ceiling(byte[] lookupKey) {
    Block.Iterator index = indexBlock.iterator(comparator);
    index.seek(lookupKey);
    if (!index.valid()) {
      return null; // beyond the last data block's last key
    }
    BlockHandle handle = BlockHandle.decode(index.value(), 0).handle();
    Block.Iterator data = dataBlockAt(handle).iterator(comparator);
    data.seek(lookupKey);
    return data.valid() ? new Entry(data.key(), data.value()) : null;
  }

  /** Every entry in ascending internal-key order. The streaming merge across tables is M4. */
  public List<Entry> entries() {
    List<Entry> out = new ArrayList<>();
    Block.Iterator index = indexBlock.iterator(comparator);
    for (index.seekToFirst(); index.valid(); index.next()) {
      BlockHandle handle = BlockHandle.decode(index.value(), 0).handle();
      Block.Iterator data = dataBlockAt(handle).iterator(comparator);
      for (data.seekToFirst(); data.valid(); data.next()) {
        out.add(new Entry(data.key(), data.value()));
      }
    }
    return out;
  }

  /** Adds a reference; pair with {@link #release}. */
  public void retain() {
    references.incrementAndGet();
  }

  /** Drops a reference; the last release closes the channel. */
  public void release() {
    if (references.decrementAndGet() == 0) {
      try {
        channel.close();
      } catch (IOException e) {
        throw new StorageException("closing SSTable " + path, e);
      }
    }
  }

  @Override
  public void close() {
    release();
  }

  private Block dataBlockAt(BlockHandle handle) {
    try {
      return new Block(readBlock(channel, fileSizeBytes, handle));
    } catch (IOException e) {
      throw new StorageException("reading SSTable " + path, e);
    }
  }

  /** Reads and CRC-verifies one block, returning its content (without the trailer). */
  private static byte[] readBlock(FileChannel channel, long fileSize, BlockHandle handle)
      throws IOException {
    long offset = handle.offsetBytes();
    int size = Math.toIntExact(handle.sizeBytes());
    int frameSize = size + SSTableFormat.BLOCK_TRAILER_SIZE;
    if (offset < 0 || offset + frameSize > fileSize) {
      throw new CorruptionException("block overruns file", offset, fileSize, offset + frameSize);
    }
    byte[] frame = readAt(channel, offset, frameSize);
    int storedCrc = LittleEndian.getFixed32(frame, size + 1);
    int actualCrc = Crc32c.of(frame, 0, size + 1); // over content ‖ type
    if (storedCrc != actualCrc) {
      throw new CorruptionException("SSTable block CRC mismatch", offset, storedCrc, actualCrc);
    }
    if (frame[size] != SSTableFormat.BLOCK_TYPE_NONE) {
      throw new CorruptionException("unknown block type", offset + size, 0, frame[size]);
    }
    return Arrays.copyOfRange(frame, 0, size);
  }

  private static byte[] readAt(FileChannel channel, long position, int length) throws IOException {
    ByteBuffer buffer = ByteBuffer.allocate(length);
    int read = 0;
    while (read < length) {
      int n = channel.read(buffer, position + read);
      if (n < 0) {
        throw new CorruptionException("unexpected end of file", position + read, length, read);
      }
      read += n;
    }
    return buffer.array();
  }

  /**
   * One stored entry. The arrays are freshly decoded and owned by the caller.
   *
   * @param internalKey the encoded internal key
   * @param value the value bytes (empty for a delete)
   */
  @Immutable
  public record Entry(byte[] internalKey, byte[] value) {}
}
