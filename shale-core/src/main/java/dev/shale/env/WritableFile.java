package dev.shale.env;

import dev.shale.internal.annotations.NotThreadSafe;
import java.io.IOException;

/**
 * A file the engine is writing: append-only, then synced, then closed. Every file Shale writes has
 * this shape — WAL segments, tables (written whole, then renamed into place), the manifest — which
 * is what lets a fault-injecting {@link Env} model a power loss exactly, by truncating each file to
 * its length at its last {@link #sync()}.
 *
 * <p>LevelDB calls this {@code WritableFile} too.
 *
 * <p><b>Threading:</b> one writer, the owner that created it. <b>Ownership:</b> its creator closes
 * it (N6).
 *
 * @see <a href="https://github.com/google/leveldb/blob/main/include/leveldb/env.h">LevelDB
 *     env.h</a>
 */
@NotThreadSafe
public interface WritableFile extends AutoCloseable {

  /**
   * Appends {@code length} bytes of {@code data} from {@code offset}; they reach the OS, not disk.
   */
  void append(byte[] data, int offset, int length) throws IOException;

  /** Appends all of {@code data}. */
  default void append(byte[] data) throws IOException {
    append(data, 0, data.length);
  }

  /**
   * Forces every byte appended so far to stable storage. The file's directory entry is a separate
   * matter: see {@link Env#syncDirectory}.
   */
  void sync() throws IOException;

  /** Bytes appended so far. */
  long size();

  @Override
  void close() throws IOException;
}
