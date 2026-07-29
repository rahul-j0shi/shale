package dev.shale.wal;

import dev.shale.Clock;
import dev.shale.Durability;
import dev.shale.Metrics;
import dev.shale.internal.annotations.NotThreadSafe;
import dev.shale.internal.coding.Crc32c;
import dev.shale.internal.coding.LittleEndian;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Appends logical records to one WAL segment as LevelDB-style fragments across 32 KiB blocks
 * (ADR-0007). Durability is chosen per call (ADR-0008): {@code NONE} writes to the page cache;
 * {@code SYNC} and {@code GROUP} {@code force()} before returning.
 *
 * <p><b>Threading:</b> single-writer; owned by the engine's write path and serialised by it.
 * <b>Ownership:</b> holds a {@link FileChannel} closed by {@link #close()}.
 *
 * @see <a href="https://github.com/google/leveldb/blob/main/db/log_writer.cc">LevelDB
 *     log_writer.cc</a>
 */
@NotThreadSafe
public final class WalWriter implements AutoCloseable {

  private final FileChannel channel;
  private final Clock clock;
  private final Metrics metrics;
  private int blockOffset;

  private WalWriter(FileChannel channel, Clock clock, Metrics metrics) {
    this.channel = channel;
    this.clock = clock;
    this.metrics = metrics;
  }

  /** Opens a fresh segment at {@code path} (which must not exist) and writes its file header. */
  public static WalWriter open(Path path, Clock clock, Metrics metrics) throws IOException {
    FileChannel channel =
        FileChannel.open(
            path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, StandardOpenOption.READ);
    byte[] header = new byte[WalFormat.FILE_HEADER_SIZE];
    LittleEndian.putFixed64(header, 0, WalFormat.MAGIC);
    LittleEndian.putFixed32(header, 8, WalFormat.FORMAT_VERSION);
    // bytes 12..15 stay zero (reserved)
    WalWriter writer = new WalWriter(channel, clock, metrics);
    writer.writeFully(ByteBuffer.wrap(header));
    return writer;
  }

  /** Appends one logical record, then satisfies {@code durability} before returning. */
  public void append(byte[] payload, Durability durability) throws IOException {
    writeRecord(payload);
    metrics.increment("wal.append.count", 1);
    if (durability != Durability.NONE) {
      sync();
    }
  }

  private void sync() throws IOException {
    long start = clock.nanoTime();
    // DURABILITY: after force(false) returns, every record appended so far survives power loss.
    channel.force(false);
    metrics.increment("wal.sync.count", 1);
    metrics.record("wal.sync.duration", clock.nanoTime() - start);
  }

  private void writeRecord(byte[] payload) throws IOException {
    int left = payload.length;
    int cursor = 0;
    boolean begin = true;
    do {
      int leftover = WalFormat.BLOCK_SIZE - blockOffset;
      if (leftover < WalFormat.FRAGMENT_HEADER_SIZE) {
        if (leftover > 0) {
          writeFully(ByteBuffer.wrap(new byte[leftover])); // zero-pad the block tail
        }
        blockOffset = 0;
      }
      int available = WalFormat.BLOCK_SIZE - blockOffset - WalFormat.FRAGMENT_HEADER_SIZE;
      int fragmentLength = Math.min(left, available);
      boolean end = fragmentLength == left;
      writeFragment(fragmentType(begin, end), payload, cursor, fragmentLength);
      blockOffset += WalFormat.FRAGMENT_HEADER_SIZE + fragmentLength;
      cursor += fragmentLength;
      left -= fragmentLength;
      begin = false;
    } while (left > 0);
  }

  private static RecordType fragmentType(boolean begin, boolean end) {
    if (begin) {
      return end ? RecordType.FULL : RecordType.FIRST;
    }
    return end ? RecordType.LAST : RecordType.MIDDLE;
  }

  private void writeFragment(RecordType type, byte[] payload, int offset, int length)
      throws IOException {
    byte[] frame = new byte[WalFormat.FRAGMENT_HEADER_SIZE + length];
    frame[4] = (byte) length;
    frame[5] = (byte) (length >>> 8);
    frame[6] = (byte) type.code();
    System.arraycopy(payload, offset, frame, WalFormat.FRAGMENT_HEADER_SIZE, length);
    int crc = Crc32c.of(frame, 6, 1 + length); // over the type byte and the payload
    LittleEndian.putFixed32(frame, 0, crc);
    writeFully(ByteBuffer.wrap(frame));
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
