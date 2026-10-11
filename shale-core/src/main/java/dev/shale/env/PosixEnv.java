package dev.shale.env;

import dev.shale.EngineStateException;
import dev.shale.internal.annotations.NotThreadSafe;
import dev.shale.internal.annotations.ThreadSafe;
import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.stream.Stream;

/**
 * The default {@link Env}: the real file system through {@code FileChannel}. This is the only class
 * in {@code shale-core}'s main code that opens a file.
 *
 * <ul>
 *   <li>{@link WritableFile#sync()} is {@code FileChannel.force(true)}: the data and the file's
 *       metadata. (LevelDB uses {@code fdatasync}; the stronger call costs little more on an
 *       append-only file and needs no argument about which metadata a later read depends on.)
 *   <li>{@link #syncDirectory} opens the directory read-only and forces it, which issues {@code
 *       fsync} on the directory on Linux and macOS.
 *   <li>{@link #rename} is {@code Files.move} with {@code ATOMIC_MOVE}: {@code rename(2)}, which
 *       replaces the target atomically.
 *   <li>{@link #lock} is {@code FileChannel.tryLock}: an advisory lock that also stops a second
 *       engine in this JVM ({@code OverlappingFileLockException}).
 * </ul>
 *
 * <p><b>Threading:</b> stateless and thread-safe.
 *
 * @see <a href="https://github.com/google/leveldb/blob/main/util/env_posix.cc">LevelDB
 *     env_posix.cc</a>
 */
@ThreadSafe
public final class PosixEnv implements Env {

  PosixEnv() {}

  @Override
  public WritableFile newWritableFile(Path path) throws IOException {
    return new PosixWritableFile(
        FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE));
  }

  @Override
  public ReadableFile newReadableFile(Path path) throws IOException {
    return new PosixReadableFile(FileChannel.open(path, StandardOpenOption.READ));
  }

  @Override
  public byte[] readAllBytes(Path path) throws IOException {
    return Files.readAllBytes(path);
  }

  @Override
  public void rename(Path from, Path to) throws IOException {
    Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
  }

  @Override
  public void delete(Path path) throws IOException {
    Files.delete(path);
  }

  @Override
  public void syncDirectory(Path dir) throws IOException {
    try (FileChannel channel = FileChannel.open(dir, StandardOpenOption.READ)) {
      // DURABILITY: after force returns, every create, rename and delete in dir survives power
      // loss.
      channel.force(true);
    }
  }

  @Override
  public List<String> children(Path dir) throws IOException {
    try (Stream<Path> entries = Files.list(dir)) {
      return entries.map(path -> path.getFileName().toString()).toList();
    }
  }

  @Override
  public boolean exists(Path path) {
    return Files.exists(path);
  }

  @Override
  public void createDirectories(Path dir) throws IOException {
    Files.createDirectories(dir);
  }

  @Override
  public Closeable lock(Path lockFile) throws IOException {
    FileChannel channel =
        FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
    boolean locked = false;
    try {
      FileLock lock;
      try {
        lock = channel.tryLock();
      } catch (OverlappingFileLockException e) {
        lock = null; // held in this JVM
      }
      if (lock == null) {
        throw new EngineStateException("database directory is in use: " + lockFile.getParent());
      }
      locked = true;
      return channel::close; // closing the channel releases the lock
    } finally {
      if (!locked) {
        channel.close();
      }
    }
  }

  /** A {@link WritableFile} over a channel opened {@code CREATE_NEW}. */
  @NotThreadSafe
  private static final class PosixWritableFile implements WritableFile {

    private final FileChannel channel;
    private long size;

    PosixWritableFile(FileChannel channel) {
      this.channel = channel;
    }

    @Override
    public void append(byte[] data, int offset, int length) throws IOException {
      ByteBuffer buffer = ByteBuffer.wrap(data, offset, length);
      while (buffer.hasRemaining()) {
        channel.write(buffer);
      }
      size += length;
    }

    @Override
    public void sync() throws IOException {
      // DURABILITY: after force returns, every byte appended so far survives power loss, provided
      // the file's directory entry is durable too (Env#syncDirectory).
      channel.force(true);
    }

    @Override
    public long size() {
      return size;
    }

    @Override
    public void close() throws IOException {
      channel.close();
    }
  }

  /** A {@link ReadableFile} over a read-only channel; positional reads share no state. */
  @ThreadSafe
  private static final class PosixReadableFile implements ReadableFile {

    private final FileChannel channel;

    PosixReadableFile(FileChannel channel) {
      this.channel = channel;
    }

    @Override
    public int read(long position, byte[] destination, int offset, int length) throws IOException {
      ByteBuffer buffer = ByteBuffer.wrap(destination, offset, length);
      while (buffer.hasRemaining()) {
        int n = channel.read(buffer, position + buffer.position() - offset);
        if (n < 0) {
          break;
        }
      }
      return buffer.position() - offset;
    }

    @Override
    public long size() throws IOException {
      return channel.size();
    }

    @Override
    public void close() throws IOException {
      channel.close();
    }
  }
}
