package dev.shale;

import dev.shale.env.Env;
import dev.shale.internal.annotations.NotThreadSafe;
import dev.shale.internal.key.InternalKey;
import dev.shale.internal.key.InternalKeyComparator;
import dev.shale.internal.log.LogReadResult;
import dev.shale.iterator.InternalIterator;
import dev.shale.manifest.CurrentFile;
import dev.shale.manifest.FileMetadata;
import dev.shale.manifest.ManifestState;
import dev.shale.manifest.ManifestWriter;
import dev.shale.manifest.Version;
import dev.shale.manifest.VersionEdit;
import dev.shale.manifest.VersionSet;
import dev.shale.memtable.Memtable;
import dev.shale.sstable.SSTableReader;
import dev.shale.wal.WalReader;
import dev.shale.wal.WalRecordCodec;
import dev.shale.wal.WalWriter;
import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Opening a database: ADR-0012 §2.3, one step per method, in order. {@link Shale#open} has taken
 * {@code LOCK} before this runs.
 *
 * <ol>
 *   <li>delete every {@code *.tmp}: an unfinished write, never read;
 *   <li>load the state from the manifest {@code CURRENT} names — or, in a directory written before
 *       M5, from the tables present — or start empty;
 *   <li>open every table the state names; a missing one is corruption naming the manifest;
 *   <li>replay the WAL segments the state still needs; only the newest may end torn (ADR-0024);
 *   <li>allocate every file number this open needs, before it writes anything;
 *   <li>flush what the WAL held to a level-0 table;
 *   <li>write a new manifest holding the whole state, and sync it;
 *   <li>switch {@code CURRENT} to it: <b>the commit point</b>;
 *   <li>create the new WAL segment;
 *   <li>delete what the new state does not need: other manifests, older segments, orphan tables;
 *   <li>hand the engine its {@link VersionSet} and WAL.
 * </ol>
 *
 * <p><b>Rollback (SH-02).</b> Everything acquired — each table reader, the manifest writer, the WAL
 * writer — is recorded as it is acquired, and released in reverse if any step throws. <b>A refused
 * open deletes nothing</b> (ADR-0024 §4): the only deletions are step 10's, after the commit point.
 * A crash before step 8 leaves the old {@code CURRENT} authoritative; one after it, the new state.
 * Either way, what the interrupted open wrote is an orphan the next open deletes.
 *
 * <p><b>Threading:</b> one instance per open, on the opening thread.
 *
 * @see <a href="https://github.com/google/leveldb/blob/main/db/db_impl.cc">LevelDB db_impl.cc
 *     (DB::Open, Recover, RemoveObsoleteFiles)</a>
 */
@NotThreadSafe
final class Recovery {

  /** The one comparator of M5; the manifest records its name, and a mismatch refuses to open. */
  static final KeyComparator COMPARATOR = BytewiseComparator.INSTANCE;

  static final InternalKeyComparator ORDERING = new InternalKeyComparator(COMPARATOR);

  private final Request request;
  private final Env env;
  private final Path directory;
  private final Metrics metrics;

  /** What to release, in reverse, if a step throws. */
  private final List<Closeable> acquired = new ArrayList<>();

  /** A reader per live table, keyed by file number; one reference each, recovery's own. */
  private final Map<Long, SSTableReader> readers = new HashMap<>();

  /** The entry in {@link #acquired} that releases each reader, so one can be given back early. */
  private final Map<Long, Closeable> releasers = new HashMap<>();

  private ManifestState state = ManifestState.EMPTY;

  /** The manifest the state came from, for error messages; empty for a pre-M5 directory. */
  private Optional<Path> manifest = Optional.empty();

  private Recovery(Request request) {
    this.request = request;
    this.env = request.options().env();
    this.directory = request.directory();
    this.metrics = request.metrics();
  }

  /**
   * What {@link Shale#open} was asked to open.
   *
   * @param directory the database directory
   * @param options the engine's options, including its {@link Env}
   * @param clock the engine's clock
   * @param metrics the engine's metrics
   */
  record Request(Path directory, ShaleOptions options, Clock clock, Metrics metrics) {}

  /**
   * What an open hands the engine.
   *
   * @param request what was opened
   * @param lock the directory's {@code LOCK}
   * @param versions the version set, holding the new manifest and the current Version
   * @param wal the new WAL segment's writer
   * @param walNumber its number
   * @param nextSequence the next sequence number to stamp
   */
  record Opened(
      Request request,
      Closeable lock,
      VersionSet versions,
      WalWriter wal,
      long walNumber,
      long nextSequence) {

    Env env() {
      return request.options().env();
    }

    Path directory() {
      return request.directory();
    }

    Clock clock() {
      return request.clock();
    }

    Metrics metrics() {
      return request.metrics();
    }

    ShaleOptions options() {
      return request.options();
    }

    KeyComparator userComparator() {
      return COMPARATOR;
    }

    InternalKeyComparator ordering() {
      return ORDERING;
    }
  }

  /** Runs recovery under {@code lock}; on any failure, releases what it acquired and rethrows. */
  static Opened run(Request request, Closeable lock) throws IOException {
    Recovery recovery = new Recovery(request);
    boolean opened = false;
    try {
      Opened result = recovery.recover(lock);
      opened = true;
      return result;
    } catch (IOException | ShaleException e) {
      recovery.rollback(e);
      throw e;
    } finally {
      if (!opened) {
        recovery.rollback(null); // an Error or another RuntimeException: still release
      }
    }
  }

  private Opened recover(Closeable lock) throws IOException {
    deleteTemporaryFiles();
    loadState();
    openTables();
    Memtable replayed = Shale.newMemtable(COMPARATOR);
    long lastSequence = Math.max(state.lastSequence(), replayWal(replayed));

    long next = Math.max(state.nextFileNumber(), FileNames.nextAfter(largestNumberOnDisk()));
    long tableNumber = 0;
    if (replayed.sizeBytes() > 0) {
      tableNumber = next;
      next = FileNames.nextAfter(next);
    }
    long walNumber = next;
    long manifestNumber = FileNames.nextAfter(walNumber);
    next = FileNames.nextAfter(manifestNumber);

    if (replayed.sizeBytes() > 0) {
      flushReplayed(tableNumber, replayed);
    }
    state =
        new ManifestState(
            Optional.of(COMPARATOR.name()), walNumber, next, lastSequence, state.files());
    ManifestWriter writer = writeManifest(manifestNumber);
    CurrentFile.write(env, directory, manifestNumber); // the commit point of open
    WalWriter wal =
        createSegment(env, directory.resolve(FileNames.wal(walNumber)), request.clock(), metrics);
    acquired.add(wal::close);
    deleteObsoleteFiles(manifestNumber, walNumber);

    Version version = new Version(state.files(), readers); // retains every reader
    VersionSet versions =
        new VersionSet(env, directory, metrics, writer, manifestNumber, state, version);
    readers.values().forEach(SSTableReader::release); // the Version holds them now
    acquired.clear();
    return new Opened(request, lock, versions, wal, walNumber, lastSequence + 1);
  }

  /** Step 1. */
  private void deleteTemporaryFiles() throws IOException {
    for (String name : env.children(directory)) {
      if (name.endsWith(Shale.TEMP_SUFFIX)) {
        env.delete(directory.resolve(name));
      }
    }
  }

  /** Step 2. */
  private void loadState() throws IOException {
    Optional<ManifestState> recorded = VersionSet.readState(env, directory);
    if (recorded.isPresent()) {
      state = recorded.get();
      manifest = CurrentFile.read(env, directory).map(directory::resolve);
      String stored = state.comparatorName().orElse(COMPARATOR.name());
      if (!stored.equals(COMPARATOR.name())) {
        throw new IllegalArgumentException(
            "database was written with comparator "
                + stored
                + "; this engine orders keys with "
                + COMPARATOR.name());
      }
      return;
    }
    migrateFromDirectory();
  }

  /**
   * A directory written before M5 has no manifest: its tables are every {@code NNNNNN.sst}, all at
   * level 0, and every WAL segment is replayed (log number 0). This is the discovery the engine
   * used to do on every open; it now runs once, and the manifest written at step 7 records it.
   */
  private void migrateFromDirectory() throws IOException {
    List<FileMetadata> files = new ArrayList<>();
    long lastSequence = 0;
    for (String name : namesInOrder(FileNames.SSTABLE)) {
      long number = FileNames.numberOf(name, FileNames.SSTABLE);
      SSTableReader reader = openTable(number);
      Optional<FileMetadata> described = describe(number, reader);
      if (described.isEmpty()) {
        readers.remove(number); // an empty table holds nothing: it is an orphan, deleted later
        acquired.remove(releasers.remove(number));
        reader.release();
        continue;
      }
      files.add(described.get());
      lastSequence = Math.max(lastSequence, described.get().largestSequence());
    }
    state = new ManifestState(Optional.empty(), 0, 1, lastSequence, files);
  }

  /** Step 3: a reader for each table the state names (a migrated directory's are open already). */
  private void openTables() throws IOException {
    for (FileMetadata file : state.files()) {
      if (readers.containsKey(file.number())) {
        continue;
      }
      Path table = directory.resolve(FileNames.sstable(file.number()));
      if (!env.exists(table)) {
        Path named = manifest.orElse(directory);
        throw new CorruptionException(
            named,
            named.getFileName() + " references missing table " + table.getFileName(),
            -1,
            file.number(),
            -1);
      }
      openTable(file.number());
    }
  }

  private SSTableReader openTable(long number) throws IOException {
    SSTableReader reader =
        SSTableReader.open(env, directory.resolve(FileNames.sstable(number)), ORDERING);
    readers.put(number, reader);
    Closeable releaser = reader::close;
    releasers.put(number, releaser);
    acquired.add(releaser);
    return reader;
  }

  /**
   * Step 4: every segment numbered at or above the log number, oldest first, into {@code into}.
   * Returns the largest sequence replayed.
   */
  private long replayWal(Memtable into) throws IOException {
    List<String> segments = new ArrayList<>();
    for (String name : namesInOrder(FileNames.WAL)) {
      if (FileNames.numberOf(name, FileNames.WAL) >= state.logNumber()) {
        segments.add(name);
      }
    }
    long largest = 0;
    for (int i = 0; i < segments.size(); i++) {
      Path segment = directory.resolve(segments.get(i));
      for (byte[] payload : replay(segment, i == segments.size() - 1)) {
        WalRecordCodec.Decoded decoded = decode(segment, payload);
        into.add(decoded.internalKey().encode(), decoded.value());
        largest = Math.max(largest, decoded.internalKey().sequenceNumber());
      }
    }
    return largest;
  }

  /**
   * The records of one WAL segment, under ADR-0024 §2's rule that <b>only the newest segment may
   * end torn</b>. Every older segment was synced when it was closed, so a crash cannot have cut it
   * short; if one ends torn, its bytes were lost or damaged some other way, and dropping its tail
   * would silently lose writes. That is corruption (N4).
   */
  private List<byte[]> replay(Path segment, boolean newest) throws IOException {
    LogReadResult result = WalReader.read(env, segment);
    if (!newest && result.tail() != LogReadResult.Tail.CLEAN) {
      throw new CorruptionException(
          segment,
          "WAL segment "
              + segment.getFileName()
              + " ends torn ("
              + result.tail()
              + ") but is not the newest: only the newest segment can be cut short by a crash",
          result.tailOffset(),
          -1,
          -1);
    }
    return result.records();
  }

  private static WalRecordCodec.Decoded decode(Path segment, byte[] payload) {
    try {
      return WalRecordCodec.decode(payload);
    } catch (CorruptionException e) {
      throw e.inFile(segment);
    }
  }

  /** Step 6. */
  private void flushReplayed(long tableNumber, Memtable replayed) throws IOException {
    Shale.Flushed flushed =
        Shale.flush(env, directory.resolve(FileNames.sstable(tableNumber)), replayed, ORDERING);
    readers.put(tableNumber, flushed.reader());
    acquired.add(flushed.reader()::close);
    state = state.apply(VersionEdit.builder().addFile(flushed.metadata()).build());
    Shale.countFlush(metrics, replayed);
  }

  /** Step 7. */
  private ManifestWriter writeManifest(long number) throws IOException {
    ManifestWriter writer =
        ManifestWriter.create(env, directory.resolve(CurrentFile.manifestName(number)));
    acquired.add(writer::close);
    writer.log(state.toEdit()); // synced: CURRENT may name it once this returns
    return writer;
  }

  /** Step 10: after the commit point, so a failure here costs only disk space until next open. */
  private void deleteObsoleteFiles(long manifestNumber, long walNumber) throws IOException {
    Set<Long> live = new HashSet<>();
    state.files().forEach(file -> live.add(file.number()));
    for (String name : env.children(directory)) {
      if (isObsolete(name, live, manifestNumber, walNumber)) {
        deleteCounted(env, directory.resolve(name), metrics);
      }
    }
  }

  /** Another manifest, a segment older than the new one, or a table the state does not name. */
  private static boolean isObsolete(
      String name, Set<Long> liveTables, long manifestNumber, long walNumber) {
    if (FileNames.is(name, FileNames.MANIFEST)) {
      return FileNames.numberOf(name, FileNames.MANIFEST) != manifestNumber;
    }
    if (FileNames.is(name, FileNames.WAL)) {
      return FileNames.numberOf(name, FileNames.WAL) < walNumber;
    }
    if (FileNames.is(name, FileNames.SSTABLE)) {
      return !liveTables.contains(FileNames.numberOf(name, FileNames.SSTABLE));
    }
    return false; // not an engine file: never touched
  }

  /**
   * Releases everything acquired, newest first. Failures here are secondary: they are attached to
   * {@code primary} when there is one, and the release goes on.
   */
  private void rollback(Throwable primary) {
    for (int i = acquired.size() - 1; i >= 0; i--) {
      try {
        acquired.get(i).close();
      } catch (IOException | ShaleException e) {
        if (primary != null) {
          primary.addSuppressed(e);
        }
      }
    }
    acquired.clear();
  }

  /** The largest file number in any engine file name present, so an orphan's is never reused. */
  private long largestNumberOnDisk() throws IOException {
    long largest = 0;
    for (String name : env.children(directory)) {
      for (Pattern kind : List.of(FileNames.WAL, FileNames.SSTABLE, FileNames.MANIFEST)) {
        if (FileNames.is(name, kind)) {
          largest = Math.max(largest, FileNames.numberOf(name, kind));
        }
      }
    }
    return largest;
  }

  private List<String> namesInOrder(Pattern kind) throws IOException {
    List<String> names = new ArrayList<>();
    for (String name : env.children(directory)) {
      if (FileNames.is(name, kind)) {
        names.add(name);
      }
    }
    names.sort(Comparator.comparingLong(name -> FileNames.numberOf(name, kind)));
    return names;
  }

  /**
   * A pre-M5 table's manifest record, read from the table itself: one pass for its first and last
   * keys and its sequence range (keys ascend by user key, so the newest entry can be anywhere).
   * Empty if the table holds no entry.
   */
  private static Optional<FileMetadata> describe(long number, SSTableReader reader) {
    byte[] first = null;
    byte[] last = null;
    long smallest = Long.MAX_VALUE;
    long largest = 0;
    try (InternalIterator entries = reader.iterator()) {
      for (entries.seekToFirst(); entries.valid(); entries.next()) {
        byte[] key = entries.internalKey();
        if (first == null) {
          first = key.clone();
        }
        last = key;
        long sequence = InternalKey.decode(key).sequenceNumber();
        smallest = Math.min(smallest, sequence);
        largest = Math.max(largest, sequence);
      }
    }
    if (first == null) {
      return Optional.empty();
    }
    return Optional.of(
        new FileMetadata(
            0, number, reader.fileSizeBytes(), smallest, largest, first, last.clone()));
  }

  /**
   * Creates WAL segment {@code path} and makes its creation durable before any record is appended:
   * {@link WalWriter#open} syncs the header, then the directory is synced. Without the directory
   * sync a power loss can drop the whole segment, synced records and all, because a file's own sync
   * does not persist its name (SH-04).
   */
  static WalWriter createSegment(Env env, Path path, Clock clock, Metrics metrics)
      throws IOException {
    WalWriter wal = WalWriter.open(env, path, clock, metrics);
    try {
      // DURABILITY: the segment's directory entry; with it, a SYNC record in it survives power
      // loss.
      env.syncDirectory(path.getParent());
    } catch (IOException e) {
      wal.close();
      throw e;
    }
    return wal;
  }

  /** Deletes {@code path}; a failure is counted, not fatal: the next open retries it. */
  static void deleteCounted(Env env, Path path, Metrics metrics) {
    try {
      env.delete(path);
    } catch (IOException e) {
      metrics.increment("file.delete.failed.count", 1);
    }
  }
}
