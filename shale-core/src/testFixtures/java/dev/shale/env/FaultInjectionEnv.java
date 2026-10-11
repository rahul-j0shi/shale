package dev.shale.env;

import dev.shale.internal.annotations.ThreadSafe;
import java.io.Closeable;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * An {@link Env} over a real directory that can crash the engine at any chosen operation and then
 * simulate a power loss, by undoing everything that was never made durable (ADR-0012, M5 plan
 * §2.7). After RocksDB's {@code FaultInjectionTestEnv}.
 *
 * <p><b>The trace.</b> Every mutating operation — create, append, sync, rename, delete, directory
 * sync — gets the next index, starting at 0, and is described in {@link #trace()}. {@link
 * #crashAt(long)} makes that operation throw {@link SimulatedCrash} instead of running, and every
 * operation after it too: the process is dead. {@link #failOn(long, IOException)} makes one
 * operation throw a real {@code IOException} and lets the rest run.
 *
 * <p><b>Power loss.</b> {@link #dropUnsyncedData(DropMode, long)} is what a machine that lost power
 * at this instant would find on reboot, under POSIX's guarantees and nothing more:
 *
 * <ul>
 *   <li>every create, rename and delete not followed by a {@link #syncDirectory} of its directory
 *       is undone, newest first: a created file is deleted; a deleted file comes back from a hidden
 *       trash directory under the root; a renamed file goes back to its old name, and the file it
 *       replaced comes back from the trash;
 *   <li>then every file written through this env is cut to its length at its last {@code sync}
 *       ({@link DropMode#DROP}), or to that length plus a seeded random prefix of what followed
 *       ({@link DropMode#TORN}).
 * </ul>
 *
 * <p>Before a directory sync, POSIX promises no order among a directory's entry changes, so a power
 * loss may keep any subset of them. {@link EntryLoss#UNDO_ALL} keeps none; {@link
 * EntryLoss#KEEP_DELETES} keeps exactly the deletes, the subset that loses the most data: a file
 * deleted because a replacement was renamed into place is gone, while the rename is not.
 *
 * <p>This is exact for Shale because every file it writes is append-only, or written once and
 * renamed into place: there is no unsynced overwrite to model. A filesystem may lose less than
 * this, never more. Files that existed before this env saw them, and directories, are treated as
 * durable.
 *
 * <p><b>Open handles.</b> {@link #openHandles()} counts the files handed out and not yet closed, so
 * a test can check that a failed {@code open} or {@code scan} returns to its baseline (SH-02,
 * SH-10). A power loss closes every handle and lock the dead process held.
 *
 * <p><b>Threading:</b> thread-safe; every method holds this env's monitor.
 *
 * @see <a href="https://github.com/facebook/rocksdb/blob/main/utilities/fault_injection_env.h">
 *     RocksDB fault_injection_env.h</a>
 */
@ThreadSafe
public final class FaultInjectionEnv implements Env {

  /** What a power loss keeps of the bytes appended after a file's last sync. */
  public enum DropMode {
    /** None of them. */
    DROP,
    /** A seeded random prefix of them: a write the disk had only partly completed. */
    TORN
  }

  /** Which of the unsynced directory-entry changes a power loss keeps. */
  public enum EntryLoss {
    /** None: every unsynced create, rename and delete is undone. */
    UNDO_ALL,
    /** The deletes; every unsynced create and rename is undone. */
    KEEP_DELETES
  }

  private static final String TRASH = ".fault-trash";

  private final Env delegate = Env.DEFAULT;
  private final Path trash;

  /** Durable length of each file this env created, keyed by its current path. @GuardedBy this */
  private final Map<Path, FileState> files = new HashMap<>();

  /** Per directory, entry changes since its last directory sync, oldest first. @GuardedBy this */
  private final Map<Path, List<DirectoryChange>> unsyncedChanges = new HashMap<>();

  /** Handles handed out and not yet closed, including locks. @GuardedBy("this") */
  private final Set<Closeable> live = new LinkedHashSet<>();

  private final Map<Long, IOException> failures = new HashMap<>(); // @GuardedBy("this")
  private final List<String> trace = new ArrayList<>(); // @GuardedBy("this")
  private long operations; // @GuardedBy("this")
  private long crashAt = -1; // @GuardedBy("this")
  private boolean crashed; // @GuardedBy("this")
  private long trashCount; // @GuardedBy("this")

  /** A fault-injecting env for files under {@code root}, an existing directory. */
  public FaultInjectionEnv(Path root) throws IOException {
    this.trash = root.toAbsolutePath().normalize().resolve(TRASH);
    Files.createDirectories(trash);
  }

  // ---------------------------------------------------------------- the test's controls

  /** Operation {@code index} throws {@link SimulatedCrash}, and so does every one after it. */
  public synchronized void crashAt(long index) {
    crashAt = index;
  }

  /** Operation {@code index} throws {@code failure} instead of running; later ones run. */
  public synchronized void failOn(long index, IOException failure) {
    failures.put(index, failure);
  }

  /** Whether a {@link SimulatedCrash} has been thrown since the last power loss. */
  public synchronized boolean crashed() {
    return crashed;
  }

  /** How many mutating operations have been attempted so far: the next one's index. */
  public synchronized long operationCount() {
    return operations;
  }

  /** One line per mutating operation, in order: {@code "<index> <operation> <file> ..."}. */
  public synchronized List<String> trace() {
    return List.copyOf(trace);
  }

  /** File handles and locks handed out and not yet closed. */
  public synchronized int openHandles() {
    return live.size();
  }

  /** As {@code dropUnsyncedData(DropMode.DROP, 0)}. */
  public void dropUnsyncedData() throws IOException {
    dropUnsyncedData(DropMode.DROP, 0);
  }

  /** As {@code dropUnsyncedData(mode, EntryLoss.UNDO_ALL, seed)}. */
  public void dropUnsyncedData(DropMode mode, long seed) throws IOException {
    dropUnsyncedData(mode, EntryLoss.UNDO_ALL, seed);
  }

  /**
   * Simulates a power loss now (see the class comment), then revives the env: the dead process's
   * handles and locks are closed, the crash point is cleared, and everything left on disk counts as
   * durable.
   */
  public synchronized void dropUnsyncedData(DropMode mode, EntryLoss entries, long seed)
      throws IOException {
    for (Closeable handle : List.copyOf(live)) {
      handle.close(); // the process died: the kernel closes its files and drops its locks
    }
    live.clear();
    List<DirectoryChange> all = new ArrayList<>();
    unsyncedChanges.values().forEach(all::addAll);
    all.sort((a, b) -> Long.compare(b.sequence(), a.sequence())); // newest first
    for (DirectoryChange change : all) {
      if (entries == EntryLoss.KEEP_DELETES && change instanceof DirectoryChange.Deleted) {
        continue; // this delete reached the disk; its file stays in the trash, emptied below
      }
      change.undo(this);
    }
    Random random = new Random(seed);
    for (Map.Entry<Path, FileState> entry : files.entrySet()) {
      truncate(entry.getKey(), entry.getValue().syncedLength(), mode, random);
    }
    files.clear();
    unsyncedChanges.clear();
    emptyTrash();
    crashed = false;
    crashAt = -1;
  }

  // ---------------------------------------------------------------- Env

  @Override
  public synchronized WritableFile newWritableFile(Path path) throws IOException {
    Path file = normal(path);
    begin("create " + file.getFileName());
    WritableFile opened = delegate.newWritableFile(file);
    FileState state = new FileState();
    files.put(file, state);
    record(file.getParent(), new DirectoryChange.Created(sequence(), file));
    FaultWritableFile handle = new FaultWritableFile(this, file, opened, state);
    live.add(handle);
    return handle;
  }

  @Override
  public synchronized ReadableFile newReadableFile(Path path) throws IOException {
    checkAlive();
    FaultReadableFile handle = new FaultReadableFile(this, delegate.newReadableFile(normal(path)));
    live.add(handle);
    return handle;
  }

  @Override
  public synchronized byte[] readAllBytes(Path path) throws IOException {
    checkAlive();
    return delegate.readAllBytes(normal(path));
  }

  @Override
  public synchronized void rename(Path from, Path to) throws IOException {
    Path source = normal(from);
    Path target = normal(to);
    begin("rename " + source.getFileName() + " " + target.getFileName());
    if (!source.getParent().equals(target.getParent())) {
      throw new IllegalArgumentException("rename across directories is not modelled: " + from);
    }
    Path replaced = null;
    if (Files.exists(target)) {
      replaced = moveToTrash(target);
    }
    delegate.rename(source, target);
    moveState(source, target);
    record(target.getParent(), new DirectoryChange.Renamed(sequence(), source, target, replaced));
  }

  @Override
  public synchronized void delete(Path path) throws IOException {
    Path file = normal(path);
    begin("delete " + file.getFileName());
    if (!Files.exists(file)) {
      delegate.delete(file); // throws NoSuchFileException, as the contract says
    }
    Path trashed = moveToTrash(file);
    record(file.getParent(), new DirectoryChange.Deleted(sequence(), file, trashed));
  }

  @Override
  public synchronized void syncDirectory(Path dir) throws IOException {
    Path directory = normal(dir);
    begin("syncdir " + directory.getFileName());
    delegate.syncDirectory(directory);
    List<DirectoryChange> changes = unsyncedChanges.remove(directory);
    if (changes != null) {
      for (DirectoryChange change : changes) {
        change.forget(); // durable now: its trash, if any, is gone for good
      }
    }
  }

  @Override
  public synchronized List<String> children(Path dir) throws IOException {
    checkAlive();
    return delegate.children(normal(dir)).stream().filter(name -> !name.equals(TRASH)).toList();
  }

  @Override
  public synchronized boolean exists(Path path) {
    checkAlive();
    return delegate.exists(normal(path));
  }

  @Override
  public synchronized void createDirectories(Path dir) throws IOException {
    checkAlive();
    delegate.createDirectories(normal(dir));
  }

  @Override
  public synchronized Closeable lock(Path lockFile) throws IOException {
    checkAlive();
    Closeable lock = delegate.lock(normal(lockFile));
    Closeable handle =
        new Closeable() {
          private boolean closed; // @GuardedBy("FaultInjectionEnv.this")

          @Override
          public void close() throws IOException {
            synchronized (FaultInjectionEnv.this) {
              if (!closed) {
                closed = true;
                live.remove(this);
                lock.close();
              }
            }
          }
        };
    live.add(handle);
    return handle;
  }

  // ---------------------------------------------------------------- internals

  /** Counts operation {@code description}; crashes or fails it if the test asked. */
  synchronized void begin(String description) throws IOException {
    checkAlive();
    long index = operations++;
    trace.add(index + " " + description);
    if (index == crashAt) {
      crashed = true;
      throw new SimulatedCrash("simulated crash at operation " + index + ": " + description);
    }
    IOException failure = failures.remove(index);
    if (failure != null) {
      throw failure;
    }
  }

  synchronized void checkAlive() {
    if (crashed) {
      throw new SimulatedCrash("the process crashed; nothing runs until dropUnsyncedData()");
    }
  }

  synchronized void closed(Closeable handle) {
    live.remove(handle);
  }

  private long sequence() {
    return operations - 1; // the index of the operation being recorded
  }

  private void record(Path directory, DirectoryChange change) {
    unsyncedChanges.computeIfAbsent(directory, d -> new ArrayList<>()).add(change);
  }

  private Path moveToTrash(Path file) throws IOException {
    Path destination = trash.resolve((trashCount++) + "-" + file.getFileName());
    Files.move(file, destination);
    moveState(file, destination);
    return destination;
  }

  /**
   * Moves a file back during undo, carrying its durable-length record with it. Nothing to move if a
   * later change, kept by {@link EntryLoss#KEEP_DELETES}, already removed it.
   */
  void restore(Path from, Path to) throws IOException {
    if (Files.exists(from)) {
      Files.move(from, to);
      moveState(from, to);
    }
  }

  void undoCreate(Path file) throws IOException {
    Files.deleteIfExists(file);
    files.remove(file);
  }

  private void moveState(Path from, Path to) {
    FileState state = files.remove(from);
    if (state != null) {
      files.put(to, state);
    }
  }

  private void truncate(Path file, long synced, DropMode mode, Random random) throws IOException {
    if (!Files.exists(file) || file.startsWith(trash)) {
      return; // undone, or deleted for good: nothing to cut
    }
    long size = Files.size(file);
    long keep = synced;
    if (mode == DropMode.TORN && size > synced) {
      keep += (long) (random.nextDouble() * (size - synced + 1));
    }
    if (keep < size) {
      try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
        channel.truncate(keep);
      }
    }
  }

  private void emptyTrash() throws IOException {
    for (String name : delegate.children(trash)) {
      Files.delete(trash.resolve(name));
    }
  }

  private static Path normal(Path path) {
    return path.toAbsolutePath().normalize();
  }

  /** How much of a file is durable: its length at its last sync. */
  static final class FileState {
    private long syncedLength; // @GuardedBy("FaultInjectionEnv.this")

    long syncedLength() {
      return syncedLength;
    }

    void synced(long length) {
      syncedLength = length;
    }
  }

  /** One directory-entry change not yet made durable by a directory sync, and how to undo it. */
  sealed interface DirectoryChange {

    long sequence();

    void undo(FaultInjectionEnv env) throws IOException;

    /** Called when its directory is synced: the change is durable and needs no undo. */
    default void forget() throws IOException {}

    record Created(long sequence, Path file) implements DirectoryChange {
      @Override
      public void undo(FaultInjectionEnv env) throws IOException {
        env.undoCreate(file);
      }
    }

    record Deleted(long sequence, Path file, Path trashed) implements DirectoryChange {
      @Override
      public void undo(FaultInjectionEnv env) throws IOException {
        env.restore(trashed, file);
      }

      @Override
      public void forget() throws IOException {
        Files.deleteIfExists(trashed);
      }
    }

    record Renamed(long sequence, Path from, Path to, Path replaced) implements DirectoryChange {
      @Override
      public void undo(FaultInjectionEnv env) throws IOException {
        env.restore(to, from);
        if (replaced != null) {
          env.restore(replaced, to);
        }
      }

      @Override
      public void forget() throws IOException {
        if (replaced != null) {
          Files.deleteIfExists(replaced);
        }
      }
    }
  }

  /** A {@link WritableFile} whose appends and syncs are counted, and whose sync is remembered. */
  private static final class FaultWritableFile implements WritableFile, Closeable {

    private final FaultInjectionEnv env;
    private final Path path;
    private final WritableFile delegate;
    private final FileState state;
    private boolean closed; // @GuardedBy("env")

    FaultWritableFile(FaultInjectionEnv env, Path path, WritableFile delegate, FileState state) {
      this.env = env;
      this.path = path;
      this.delegate = delegate;
      this.state = state;
    }

    @Override
    public void append(byte[] data, int offset, int length) throws IOException {
      synchronized (env) {
        env.begin("append " + path.getFileName() + " " + length);
        delegate.append(data, offset, length);
      }
    }

    @Override
    public void sync() throws IOException {
      synchronized (env) {
        env.begin("sync " + path.getFileName());
        delegate.sync();
        state.synced(delegate.size());
      }
    }

    @Override
    public long size() {
      return delegate.size();
    }

    @Override
    public void close() throws IOException {
      synchronized (env) {
        if (!closed) {
          closed = true;
          env.closed(this);
          delegate.close();
        }
      }
    }
  }

  /** A {@link ReadableFile} that is counted while open; reads are not mutating operations. */
  private static final class FaultReadableFile implements ReadableFile, Closeable {

    private final FaultInjectionEnv env;
    private final ReadableFile delegate;
    private boolean closed; // @GuardedBy("env")

    FaultReadableFile(FaultInjectionEnv env, ReadableFile delegate) {
      this.env = env;
      this.delegate = delegate;
    }

    @Override
    public int read(long position, byte[] destination, int offset, int length) throws IOException {
      env.checkAlive();
      return delegate.read(position, destination, offset, length);
    }

    @Override
    public long size() throws IOException {
      return delegate.size();
    }

    @Override
    public void close() throws IOException {
      synchronized (env) {
        if (!closed) {
          closed = true;
          env.closed(this);
          delegate.close();
        }
      }
    }
  }
}
