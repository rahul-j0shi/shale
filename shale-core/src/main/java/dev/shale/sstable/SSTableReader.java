package dev.shale.sstable;

import dev.shale.CorruptionException;
import dev.shale.KeyComparator;
import dev.shale.StorageException;
import dev.shale.env.Env;
import dev.shale.env.ReadableFile;
import dev.shale.internal.annotations.Immutable;
import dev.shale.internal.annotations.ThreadSafe;
import dev.shale.internal.coding.Crc32c;
import dev.shale.internal.coding.LittleEndian;
import dev.shale.iterator.InternalIterator;
import dev.shale.iterator.ReferenceCounted;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Reads an SSTable back (format.md §1). {@link #open} verifies the footer and loads the index
 * block; {@link #ceiling} resolves a point lookup index → data block → restart search, and {@link
 * #iterator} streams the whole table in order. Data blocks are read from the file on demand and
 * their CRC verified before use (N4).
 *
 * <p>The two read shapes are deliberate and must agree about where a target resolves: {@code
 * ceiling} serves the engine's point lookup in one descent, {@code iterator} feeds the merge that
 * spans every source (ADR-0011).
 *
 * <p><b>Threading:</b> thread-safe — the index block is immutable and data-block reads are
 * positional ({@link ReadableFile#read}), so concurrent readers do not interfere. <b>Ownership:</b>
 * reference-counted (N6): {@link #open} returns one reference; {@link #release} drops one and
 * closes the file at zero. File deletion on the last release arrives with the manifest (M5); at M3
 * nothing deletes a table.
 *
 * @see <a href="https://github.com/google/leveldb/blob/main/table/table.cc">LevelDB table.cc</a>
 */
@ThreadSafe
public final class SSTableReader implements AutoCloseable, ReferenceCounted {

  private final Path path;
  private final ReadableFile file;
  private final long fileSizeBytes;
  private final KeyComparator comparator;
  private final Block indexBlock;
  private final AtomicInteger references = new AtomicInteger(1);

  private SSTableReader(
      Path path,
      ReadableFile file,
      long fileSizeBytes,
      KeyComparator comparator,
      Block indexBlock) {
    this.path = path;
    this.file = file;
    this.fileSizeBytes = fileSizeBytes;
    this.comparator = comparator;
    this.indexBlock = indexBlock;
  }

  /**
   * Opens {@code path}, verifying the footer and loading the index; the caller holds one reference.
   */
  public static SSTableReader open(Path path, KeyComparator comparator) throws IOException {
    return open(Env.DEFAULT, path, comparator);
  }

  /** As {@link #open(Path, KeyComparator)}, through {@code env}. */
  public static SSTableReader open(Env env, Path path, KeyComparator comparator)
      throws IOException {
    ReadableFile file = env.newReadableFile(path);
    boolean opened = false;
    try {
      long size = file.size();
      if (size < SSTableFormat.FOOTER_SIZE) {
        throw new CorruptionException(
            "file smaller than a footer", 0, SSTableFormat.FOOTER_SIZE, size);
      }
      byte[] footerBytes =
          readAt(file, size - SSTableFormat.FOOTER_SIZE, SSTableFormat.FOOTER_SIZE);
      Footer footer = Footer.decode(footerBytes, size - SSTableFormat.FOOTER_SIZE);
      validateLayout(footer, size - SSTableFormat.FOOTER_SIZE);
      Block indexBlock = new Block(readBlock(file, size, footer.indexHandle()));
      validateDataHandles(indexBlock, footer.metaindexHandle().offsetBytes(), comparator);
      SSTableReader reader = new SSTableReader(path, file, size, comparator, indexBlock);
      opened = true;
      return reader;
    } catch (CorruptionException e) {
      throw e.inFile(path);
    } finally {
      if (!opened) {
        file.close(); // a bad footer/index must not leak the handle
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

  /**
   * A fresh, unpositioned cursor over every entry in ascending internal-key order, reading one data
   * block at a time.
   *
   * <p>Replaced the materialising {@code entries()} at M4 (ADR-0011): that method decoded the whole
   * table into a list, so iterating a large table cost the table rather than the cursor.
   *
   * <p>The returned iterator reads through this reader's file, so it is only usable while a
   * reference is held. It does <em>not</em> take one itself — the cursor that owns the read does
   * (N6), because that is the object whose lifetime matches the caller's.
   */
  public InternalIterator iterator() {
    return new SSTableIterator(comparator, indexBlock, this::dataBlockAt);
  }

  /** Adds a reference; pair with {@link #release}. */
  @Override
  public void retain() {
    references.incrementAndGet();
  }

  /** Drops a reference; the last release closes the file. */
  @Override
  public void release() {
    if (references.decrementAndGet() == 0) {
      try {
        file.close();
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
      return new Block(readBlock(file, fileSizeBytes, handle));
    } catch (CorruptionException e) {
      throw e.inFile(path);
    } catch (IOException e) {
      throw new StorageException("reading SSTable " + path, e);
    }
  }

  /**
   * The v1 file layout, checked before any block is trusted (ADR-0024 §6): the writer lays out data
   * blocks, then the metaindex, then the index, then the footer, each block followed by its trailer
   * and nothing in between. A footer whose handles break that order — the review rewrote one to
   * name the valid, empty metaindex as the index, so the table opened with no data (SH-15) — is
   * corruption, however good each block's CRC. Checked by subtraction, so nothing can overflow.
   */
  private static void validateLayout(Footer footer, long footerStart) {
    BlockHandle index = footer.indexHandle();
    BlockHandle metaindex = footer.metaindexHandle();
    if (!endsAt(index, footerStart)) {
      throw new CorruptionException(
          "SSTable index block does not end where the footer begins",
          index.offsetBytes(),
          footerStart,
          index.offsetBytes() + index.sizeBytes() + SSTableFormat.BLOCK_TRAILER_SIZE);
    }
    if (!endsAt(metaindex, index.offsetBytes())) {
      throw new CorruptionException(
          "SSTable metaindex block does not end where the index block begins",
          metaindex.offsetBytes(),
          index.offsetBytes(),
          metaindex.offsetBytes() + metaindex.sizeBytes() + SSTableFormat.BLOCK_TRAILER_SIZE);
    }
  }

  /** Whether {@code handle}'s block, with its trailer, ends exactly at {@code end}. */
  private static boolean endsAt(BlockHandle handle, long end) {
    long offset = handle.offsetBytes();
    long size = handle.sizeBytes();
    return offset >= 0
        && size >= 0
        && offset <= end
        && size == end - offset - SSTableFormat.BLOCK_TRAILER_SIZE;
  }

  /**
   * Every data block handle in the index lies wholly before the metaindex, in increasing offset
   * order, without overlap, and is encoded with nothing after it. A handle that pointed anywhere
   * else would read another block in a data block's role.
   */
  private static void validateDataHandles(
      Block indexBlock, long dataRegionEnd, KeyComparator comparator) {
    Block.Iterator index = indexBlock.iterator(comparator);
    long previousEnd = 0;
    for (index.seekToFirst(); index.valid(); index.next()) {
      BlockHandle.Decoded decoded = BlockHandle.decode(index.value(), 0);
      BlockHandle handle = decoded.handle();
      long offset = handle.offsetBytes();
      long size = handle.sizeBytes();
      boolean inRegion =
          offset >= previousEnd
              && size >= 0
              && offset <= dataRegionEnd
              && size <= dataRegionEnd - offset - SSTableFormat.BLOCK_TRAILER_SIZE;
      if (!inRegion || decoded.nextOffset() != index.value().length) {
        throw new CorruptionException(
            "SSTable data block handle outside the data region", offset, dataRegionEnd, size);
      }
      previousEnd = offset + size + SSTableFormat.BLOCK_TRAILER_SIZE;
    }
  }

  /**
   * Reads and CRC-verifies one block, returning its content (without the trailer). The handle's
   * offset and size are checked against the file by subtraction on {@code long}s before anything is
   * narrowed or allocated, so a malformed size is {@link CorruptionException}, never an {@code
   * ArithmeticException} or an out-of-bounds read (SH-16).
   */
  private static byte[] readBlock(ReadableFile file, long fileSize, BlockHandle handle)
      throws IOException {
    long offset = handle.offsetBytes();
    long contentSize = handle.sizeBytes();
    if (offset < 0 || offset > fileSize) {
      throw new CorruptionException(
          "SSTable block offset outside the file", offset, fileSize, offset);
    }
    long room = fileSize - offset - SSTableFormat.BLOCK_TRAILER_SIZE;
    if (contentSize < 0 || contentSize > room) {
      throw new CorruptionException("SSTable block overruns the file", offset, room, contentSize);
    }
    if (contentSize > Integer.MAX_VALUE - SSTableFormat.BLOCK_TRAILER_SIZE) {
      throw new CorruptionException(
          "SSTable block too large to read", offset, Integer.MAX_VALUE, contentSize);
    }
    int size = (int) contentSize;
    int frameSize = size + SSTableFormat.BLOCK_TRAILER_SIZE;
    byte[] frame = readAt(file, offset, frameSize);
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

  private static byte[] readAt(ReadableFile file, long position, int length) throws IOException {
    byte[] bytes = new byte[length];
    int read = file.read(position, bytes, 0, length);
    if (read < length) {
      throw new CorruptionException("unexpected end of file", position + read, length, read);
    }
    return bytes;
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
