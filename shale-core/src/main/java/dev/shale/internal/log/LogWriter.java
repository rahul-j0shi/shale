package dev.shale.internal.log;

import dev.shale.env.Env;
import dev.shale.env.WritableFile;
import dev.shale.internal.annotations.NotThreadSafe;
import dev.shale.internal.coding.Crc32c;
import dev.shale.internal.coding.LittleEndian;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Appends logical records to one log file as fragments across 32 KiB blocks ({@code format.md}). It
 * frames and writes; it does not decide durability: the log that owns it calls {@link #sync()} when
 * its own contract requires.
 *
 * <p><b>Threading:</b> single-writer; owned by the log that created it. <b>Ownership:</b> holds a
 * {@link WritableFile} closed by {@link #close()}.
 *
 * @see <a href="https://github.com/google/leveldb/blob/main/db/log_writer.cc">LevelDB
 *     log_writer.cc</a>
 */
@NotThreadSafe
public final class LogWriter implements AutoCloseable {

  private final WritableFile file;
  private final int headerSize;
  private int blockOffset;

  private LogWriter(WritableFile file, int version) {
    this.file = file;
    this.headerSize = LogFormat.fragmentHeaderSize(version);
  }

  /**
   * Creates {@code path} (which must not exist) and writes a file header naming {@code magic} and
   * framing {@code version}.
   */
  public static LogWriter create(Path path, long magic, int version) throws IOException {
    return create(Env.DEFAULT, path, magic, version);
  }

  /** As {@link #create(Path, long, int)}, through {@code env}. */
  public static LogWriter create(Env env, Path path, long magic, int version) throws IOException {
    WritableFile file = env.newWritableFile(path);
    boolean created = false;
    try {
      LogWriter writer = new LogWriter(file, version);
      byte[] header = new byte[LogFormat.FILE_HEADER_SIZE];
      LittleEndian.putFixed64(header, 0, magic);
      LittleEndian.putFixed32(header, 8, version);
      // bytes 12..15 stay zero (reserved)
      file.append(header);
      created = true;
      return writer;
    } finally {
      if (!created) {
        file.close(); // a failed header write must not leak the handle (N6)
      }
    }
  }

  /** Appends one logical record. It reaches the OS page cache, not the disk: see {@link #sync}. */
  public void append(byte[] payload) throws IOException {
    int left = payload.length;
    int cursor = 0;
    boolean begin = true;
    do {
      int leftover = LogFormat.BLOCK_SIZE - blockOffset;
      if (leftover < headerSize) {
        if (leftover > 0) {
          file.append(new byte[leftover]); // zero-pad the block tail
        }
        blockOffset = 0;
      }
      int available = LogFormat.BLOCK_SIZE - blockOffset - headerSize;
      int fragmentLength = Math.min(left, available);
      boolean end = fragmentLength == left;
      writeFragment(fragmentType(begin, end), payload, cursor, fragmentLength);
      blockOffset += headerSize + fragmentLength;
      cursor += fragmentLength;
      left -= fragmentLength;
      begin = false;
    } while (left > 0);
  }

  /** Bytes written to the file so far, header and padding included. */
  public long sizeBytes() {
    return file.size();
  }

  /** Forces every record appended so far to stable storage. */
  public void sync() throws IOException {
    // DURABILITY: after sync returns, every record appended so far survives power loss, provided
    // the file's directory entry is durable too (M5 syncs it; ADR-0024 §7).
    file.sync();
  }

  private static RecordType fragmentType(boolean begin, boolean end) {
    if (begin) {
      return end ? RecordType.FULL : RecordType.FIRST;
    }
    return end ? RecordType.LAST : RecordType.MIDDLE;
  }

  private void writeFragment(RecordType type, byte[] payload, int offset, int length)
      throws IOException {
    byte[] frame = new byte[headerSize + length];
    frame[4] = (byte) length;
    frame[5] = (byte) (length >>> 8);
    frame[6] = (byte) type.code();
    System.arraycopy(payload, offset, frame, headerSize, length);
    int payloadCrc = Crc32c.of(frame[6], frame, headerSize, length); // over type, then payload
    LittleEndian.putFixed32(frame, 0, payloadCrc);
    if (headerSize > LogFormat.CHECKED_HEADER_BYTES) {
      // v2: a checksum over the seven bytes above, so a reader can trust length and type before
      // it uses them to decide anything — including that a short file is a torn tail (ADR-0024).
      int headerCrc = Crc32c.of(frame, 0, LogFormat.CHECKED_HEADER_BYTES);
      LittleEndian.putFixed32(frame, LogFormat.CHECKED_HEADER_BYTES, headerCrc);
    }
    file.append(frame);
  }

  @Override
  public void close() throws IOException {
    file.close();
  }
}
