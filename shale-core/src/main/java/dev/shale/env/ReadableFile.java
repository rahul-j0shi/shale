package dev.shale.env;

import dev.shale.internal.annotations.ThreadSafe;
import java.io.IOException;

/**
 * A file the engine reads at arbitrary positions, such as an SSTable. LevelDB's {@code
 * RandomAccessFile}, renamed so it cannot be confused with {@link java.io.RandomAccessFile}.
 *
 * <p><b>Threading:</b> thread-safe: reads are positional, so concurrent readers do not share a file
 * pointer. <b>Ownership:</b> its opener closes it (N6).
 *
 * @see <a href="https://github.com/google/leveldb/blob/main/include/leveldb/env.h">LevelDB
 *     env.h</a>
 */
@ThreadSafe
public interface ReadableFile extends AutoCloseable {

  /**
   * Reads up to {@code length} bytes at {@code position} into {@code destination} from {@code
   * offset}, returning how many were read: fewer than {@code length} only at the end of the file.
   */
  int read(long position, byte[] destination, int offset, int length) throws IOException;

  /** The file's size in bytes. */
  long size() throws IOException;

  @Override
  void close() throws IOException;
}
