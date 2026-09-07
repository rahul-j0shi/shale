package dev.shale.internal.blocklog;

import dev.shale.internal.annotations.NotThreadSafe;
import dev.shale.internal.coding.Crc32c;
import dev.shale.internal.coding.LittleEndian;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Appends logical records to one block-log file as fragments across fixed-size blocks (ADR-0007).
 *
 * <p>This type owns framing and nothing else. It does not decide when to {@code force()} — that is
 * a durability policy belonging to the caller (N3) — and it does not emit metrics. The WAL layers
 * {@link dev.shale.Durability} on top; the manifest forces once per install.
 *
 * <p><b>Threading:</b> single-writer; owned and serialised by its caller. <b>Ownership:</b> holds a
 * {@link FileChannel} closed by {@link #close()}.
 *
 * @see <a href="https://github.com/google/leveldb/blob/main/db/log_writer.cc">LevelDB
 *     log_writer.cc</a>
 */
@NotThreadSafe
public final class BlockLogWriter implements AutoCloseable {

  private final FileChannel channel;
  private final BlockLogFormat format;
  private int blockOffset;

  private BlockLogWriter(FileChannel channel, BlockLogFormat format) {
    this.channel = channel;
    this.format = format;
  }

  /** Opens a fresh file at {@code path} (which must not exist) and writes its header. */
  public static BlockLogWriter open(Path path, BlockLogFormat format) throws IOException {
    FileChannel channel =
        FileChannel.open(
            path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, StandardOpenOption.READ);
    byte[] header = new byte[BlockLogFormat.FILE_HEADER_SIZE];
    LittleEndian.putFixed64(header, 0, format.magic());
    LittleEndian.putFixed32(header, 8, format.formatVersion());
    // bytes 12..15 stay zero (reserved)
    BlockLogWriter writer = new BlockLogWriter(channel, format);
    writer.writeFully(ByteBuffer.wrap(header));
    return writer;
  }

  /** Appends one logical record, fragmenting it across blocks as needed. Does not force. */
  public void append(byte[] payload) throws IOException {
    int left = payload.length;
    int cursor = 0;
    boolean begin = true;
    do {
      int leftover = BlockLogFormat.BLOCK_SIZE - blockOffset;
      if (leftover < BlockLogFormat.FRAGMENT_HEADER_SIZE) {
        if (leftover > 0) {
          writeFully(ByteBuffer.wrap(new byte[leftover])); // zero-pad the block tail
        }
        blockOffset = 0;
      }
      int available = BlockLogFormat.BLOCK_SIZE - blockOffset - BlockLogFormat.FRAGMENT_HEADER_SIZE;
      int fragmentLength = Math.min(left, available);
      boolean end = fragmentLength == left;
      writeFragment(fragmentType(begin, end), payload, cursor, fragmentLength);
      blockOffset += BlockLogFormat.FRAGMENT_HEADER_SIZE + fragmentLength;
      cursor += fragmentLength;
      left -= fragmentLength;
      begin = false;
    } while (left > 0);
  }

  /**
   * Flushes the file's data to stable storage.
   *
   * <p>The caller decides when this happens and what it promises; see {@code Durability} for the
   * WAL's policy and {@code ManifestWriter} for the manifest's.
   */
  public void force() throws IOException {
    // DURABILITY: after force(false) returns, every record appended so far survives power loss.
    channel.force(false);
  }

  /** The name this log reports itself as in corruption messages. */
  public BlockLogFormat format() {
    return format;
  }

  private static FragmentType fragmentType(boolean begin, boolean end) {
    if (begin) {
      return end ? FragmentType.FULL : FragmentType.FIRST;
    }
    return end ? FragmentType.LAST : FragmentType.MIDDLE;
  }

  private void writeFragment(FragmentType type, byte[] payload, int offset, int length)
      throws IOException {
    byte[] frame = new byte[BlockLogFormat.FRAGMENT_HEADER_SIZE + length];
    frame[4] = (byte) length;
    frame[5] = (byte) (length >>> 8);
    frame[6] = (byte) type.code();
    System.arraycopy(payload, offset, frame, BlockLogFormat.FRAGMENT_HEADER_SIZE, length);
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
