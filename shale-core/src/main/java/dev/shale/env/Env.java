package dev.shale.env;

import dev.shale.internal.annotations.ThreadSafe;
import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Every file-system operation the engine performs, and nothing else (ADR-0012). LevelDB's {@code
 * Env}, cut down to what Shale uses: no threads, no clock (Shale injects a {@link dev.shale.Clock}
 * separately, ADR-0008), no logger.
 *
 * <p><b>The contract a crash test relies on.</b> Data appended to a {@link WritableFile} survives a
 * power loss only once {@link WritableFile#sync()} has returned; a create, rename or delete
 * survives only once {@link #syncDirectory} of its directory has returned. An implementation may
 * lose anything not yet synced, and a fault-injecting one does, on purpose.
 *
 * <p><b>Threading:</b> implementations are thread-safe.
 *
 * @see <a href="https://github.com/google/leveldb/blob/main/include/leveldb/env.h">LevelDB
 *     env.h</a>
 */
@ThreadSafe
public interface Env {

  /** The real file system, through {@code FileChannel}. */
  Env DEFAULT = new PosixEnv();

  /** Creates {@code path}, which must not exist, for appending. */
  WritableFile newWritableFile(Path path) throws IOException;

  /** Opens an existing {@code path} for positional reads. */
  ReadableFile newReadableFile(Path path) throws IOException;

  /** The whole content of {@code path}. */
  byte[] readAllBytes(Path path) throws IOException;

  /** Renames {@code from} to {@code to} atomically, replacing {@code to} if it exists. */
  void rename(Path from, Path to) throws IOException;

  /** Deletes {@code path}, which must exist. */
  void delete(Path path) throws IOException;

  /**
   * Makes every create, rename and delete in {@code dir} so far durable.
   *
   * <p>A file's own {@code sync} does not make its directory entry durable: after a power loss a
   * synced file can be missing, or a renamed one back under its old name (Pillai et al., OSDI
   * 2014).
   */
  void syncDirectory(Path dir) throws IOException;

  /** The names (not paths) of the entries in {@code dir}, in no particular order. */
  List<String> children(Path dir) throws IOException;

  /** Whether {@code path} exists. */
  boolean exists(Path path);

  /** Creates {@code dir} and any missing parents; does nothing if it exists. */
  void createDirectories(Path dir) throws IOException;

  /**
   * Takes an exclusive lock on {@code lockFile}, creating it if needed, and holds it until the
   * returned handle is closed.
   *
   * @throws dev.shale.EngineStateException if another holder, in this process or another, has it
   */
  Closeable lock(Path lockFile) throws IOException;
}
