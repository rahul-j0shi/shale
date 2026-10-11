package dev.shale.manifest;

import dev.shale.CorruptionException;
import dev.shale.EngineStateException;
import dev.shale.Metrics;
import dev.shale.StorageException;
import dev.shale.env.Env;
import dev.shale.internal.annotations.ThreadSafe;
import dev.shale.sstable.SSTableReader;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * The current {@link Version}, the manifest that makes it durable, and the counters (LevelDB's
 * {@code VersionSet}). Every change to the set of live tables goes through {@link #logAndApply}:
 * the edit is appended to the manifest and synced, and only then does a new Version become current.
 * Tables the edit removes are marked obsolete, so each is deleted by the release that drops its
 * last reference — never while a reader holds it, and never before the edit that removed it is
 * durable.
 *
 * <p><b>Failure.</b> If appending or syncing an edit fails, nothing is installed and the set stops
 * accepting edits: the manifest's tail is unknown, so the engine must reopen, and recovery reads
 * only what reached the disk (ADR-0012 §2.6).
 *
 * <p><b>Threading:</b> thread-safe; every method holds {@code versionLock}, which ranks below the
 * engine's {@code writeLock} (concurrency-and-resources.md §2). <b>Ownership:</b> owns the {@link
 * ManifestWriter} and one reference to the current Version, both released by {@link #close()}.
 *
 * @see <a href="https://github.com/google/leveldb/blob/main/db/version_set.cc">LevelDB
 *     version_set.cc (LogAndApply)</a>
 */
@ThreadSafe
public final class VersionSet implements AutoCloseable {

  private final Object versionLock = new Object();
  private final Env env;
  private final Path directory;
  private final Metrics metrics;

  /** The live manifest's writer. @GuardedBy("versionLock") */
  private ManifestWriter manifest;

  /** The live manifest's number. @GuardedBy("versionLock") */
  private long manifestNumber;

  /** The durable state, plus file numbers allocated since. @GuardedBy("versionLock") */
  private ManifestState state;

  /** One reference held by this set. @GuardedBy("versionLock") */
  private Version current;

  /** The manifest write that failed; once set, no edit is accepted. @GuardedBy("versionLock") */
  private IOException failure;

  /** Set once by close(). @GuardedBy("versionLock") */
  private boolean closed;

  /**
   * A set whose live manifest is {@code manifest} (number {@code manifestNumber}, already holding
   * {@code state} durably), with {@code current} as the current Version. It takes ownership of the
   * writer and of the caller's reference to {@code current}.
   */
  public VersionSet(
      Env env,
      Path directory,
      Metrics metrics,
      ManifestWriter manifest,
      long manifestNumber,
      ManifestState state,
      Version current) {
    this.env = env;
    this.directory = directory;
    this.metrics = metrics;
    this.manifest = manifest;
    this.manifestNumber = manifestNumber;
    this.state = state;
    this.current = current;
  }

  /**
   * The state the manifest named by {@code CURRENT} in {@code directory} holds, or empty if there
   * is no {@code CURRENT}.
   *
   * @throws CorruptionException if {@code CURRENT} is malformed, names a missing manifest, or the
   *     manifest is damaged or holds no edit
   */
  public static Optional<ManifestState> readState(Env env, Path directory) throws IOException {
    Optional<String> name = CurrentFile.read(env, directory);
    if (name.isEmpty()) {
      return Optional.empty();
    }
    Path path = directory.resolve(name.get());
    if (!env.exists(path)) {
      throw new CorruptionException(
          directory.resolve(CurrentFile.NAME), "CURRENT names a missing " + name.get(), 0, -1, -1);
    }
    List<VersionEdit> edits = ManifestReader.read(env, path);
    if (edits.isEmpty()) {
      throw new CorruptionException(path, "manifest holds no version edit", 16, 1, 0);
    }
    try {
      return Optional.of(ManifestState.replay(edits));
    } catch (CorruptionException e) {
      throw e.inFile(path);
    }
  }

  /**
   * The current Version, retained for the caller, who must release it. It still works after a
   * failed edit: the engine keeps serving reads from the last Version that was made durable.
   */
  public Version acquire() {
    synchronized (versionLock) {
      if (closed) {
        throw new EngineStateException("version set is closed");
      }
      current.retain();
      return current;
    }
  }

  /** The state the manifest holds, plus file numbers allocated since its last edit. */
  public ManifestState state() {
    synchronized (versionLock) {
      return state;
    }
  }

  /** The live manifest's number. */
  public long manifestNumber() {
    synchronized (versionLock) {
      return manifestNumber;
    }
  }

  /**
   * Allocates a file number. It becomes durable with the next edit, which records the counter; a
   * number allocated and never recorded is never reused, because recovery also counts every file
   * name it finds.
   */
  public long newFileNumber() {
    synchronized (versionLock) {
      long number = state.nextFileNumber();
      state = state.withNextFileNumberTaken();
      return number;
    }
  }

  /**
   * Makes {@code edit} durable, then current. The edit is completed with the next file number and
   * last sequence if it does not set them. Each table the edit adds must have a reader in {@code
   * added}, which the new Version retains; the caller keeps its own references.
   *
   * @throws IOException if the manifest write or sync fails: nothing is installed, and every later
   *     call throws {@link EngineStateException}
   */
  public void logAndApply(VersionEdit edit, Map<Long, SSTableReader> added) throws IOException {
    synchronized (versionLock) {
      requireOpen();
      VersionEdit complete = complete(edit);
      ManifestState next = state.apply(complete);
      Map<Long, SSTableReader> readers = new HashMap<>(current.readers());
      readers.putAll(added);
      try {
        manifest.log(complete); // the edit's durability point (ManifestWriter.log)
      } catch (IOException e) {
        failure = e;
        throw e;
      }
      Version installed = new Version(next.files(), readers);
      Version previous = current;
      current = installed;
      state = next;
      for (VersionEdit.DeletedFile deleted : complete.deletedFiles()) {
        previous.table(deleted.number()).markObsolete();
      }
      releaseCounted(previous);
    }
  }

  /**
   * Starts a new manifest if this one has grown past {@code thresholdBytes}: a full-state edit in a
   * new file, synced, then {@code CURRENT} switched to it, then the old one deleted (LevelDB starts
   * a new descriptor at open only; Shale also rolls over, so a long-lived database replays a short
   * manifest). Called after a flush's edit.
   *
   * @throws IOException if the new manifest or {@code CURRENT} cannot be written: the old manifest
   *     stays live, and the set stops accepting edits
   */
  public void rolloverIfLargerThan(long thresholdBytes) throws IOException {
    synchronized (versionLock) {
      requireOpen();
      if (manifest.sizeBytes() <= thresholdBytes) {
        return;
      }
      long number = state.nextFileNumber();
      state = state.withNextFileNumberTaken();
      Path path = directory.resolve(CurrentFile.manifestName(number));
      ManifestWriter next = null;
      try {
        next = ManifestWriter.create(env, path);
        next.log(state.toEdit());
        CurrentFile.write(env, directory, number); // the rollover's commit point
      } catch (IOException e) {
        failure = e;
        if (next != null) {
          next.close();
        }
        throw e;
      }
      Path old = directory.resolve(CurrentFile.manifestName(manifestNumber));
      manifest.close();
      manifest = next;
      manifestNumber = number;
      deleteCounted(old);
      metrics.increment("manifest.rollover.count", 1);
    }
  }

  /**
   * Closes the manifest and releases this set's reference to the current Version. Idempotent.
   * Tables stay on disk: closing makes nothing obsolete.
   */
  @Override
  public void close() throws IOException {
    synchronized (versionLock) {
      if (closed) {
        return;
      }
      closed = true;
      try {
        manifest.close();
      } finally {
        current.release();
      }
    }
  }

  private VersionEdit complete(VersionEdit edit) {
    return new VersionEdit(
        edit.comparatorName(),
        edit.logNumber(),
        edit.nextFileNumber().isPresent()
            ? edit.nextFileNumber()
            : OptionalLong.of(state.nextFileNumber()),
        edit.lastSequence().isPresent()
            ? edit.lastSequence()
            : OptionalLong.of(state.lastSequence()),
        edit.compactionPointers(),
        edit.deletedFiles(),
        edit.newFiles());
  }

  private void requireOpen() {
    if (closed) {
      throw new EngineStateException("version set is closed");
    }
    if (failure != null) {
      throw new EngineStateException("manifest write failed; reopen to recover", failure);
    }
  }

  /** Releases {@code version}; a table whose delete fails is counted, not fatal: it is orphaned. */
  private void releaseCounted(Version version) {
    try {
      version.release();
    } catch (StorageException e) {
      metrics.increment("file.delete.failed.count", 1 + e.getSuppressed().length);
    }
  }

  private void deleteCounted(Path path) {
    try {
      env.delete(path);
    } catch (IOException e) {
      metrics.increment("file.delete.failed.count", 1); // the next open deletes it
    }
  }
}
