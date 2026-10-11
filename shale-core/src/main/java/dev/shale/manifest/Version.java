package dev.shale.manifest;

import dev.shale.StorageException;
import dev.shale.internal.annotations.ThreadSafe;
import dev.shale.iterator.ReferenceCounted;
import dev.shale.sstable.SSTableReader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One immutable set of live tables, with a reader for each (LevelDB's {@code Version}). Reads
 * {@link #retain} a Version for as long as they read, so a table it lists cannot be deleted under
 * them, however many edits happen meanwhile.
 *
 * <p><b>Ownership:</b> reference-counted (N6). The constructor retains every reader it is given and
 * returns with one reference, owned by the caller; the release that drops the count to zero
 * releases every reader. Versions share readers: the next Version retains the same reader objects
 * the previous one had, plus its new ones, so a table's reader closes — and, once obsolete, its
 * file is deleted — only when every Version listing it and every other holder has let go.
 *
 * <p><b>Threading:</b> thread-safe: immutable but for the atomic count.
 *
 * @see <a href="https://github.com/google/leveldb/blob/main/db/version_set.h">LevelDB version_set.h
 *     (Version)</a>
 */
@ThreadSafe
public final class Version implements ReferenceCounted {

  /**
   * Newest first: level 0 by largest sequence, then file number, descending; then levels 1 to 6.
   * Within a level above 0, by file number until M6 orders those by key range.
   */
  private static final Comparator<FileMetadata> NEWEST_FIRST =
      Comparator.comparingInt(FileMetadata::level)
          .thenComparing(
              Comparator.comparingLong(FileMetadata::largestSequence)
                  .thenComparingLong(FileMetadata::number)
                  .reversed());

  private final List<FileMetadata> files;
  private final Map<Long, SSTableReader> readers;
  private final AtomicInteger references = new AtomicInteger(1);

  /**
   * A Version of {@code files}, reading each through its reader in {@code readers} (keyed by file
   * number), each of which it retains.
   *
   * @throws IllegalArgumentException if a file has no reader
   */
  public Version(List<FileMetadata> files, Map<Long, SSTableReader> readers) {
    List<FileMetadata> ordered = new ArrayList<>(files);
    ordered.sort(NEWEST_FIRST);
    Map<Long, SSTableReader> mine = new HashMap<>();
    for (FileMetadata file : ordered) {
      SSTableReader reader = readers.get(file.number());
      if (reader == null) {
        throw new IllegalArgumentException("no reader for table " + file.number());
      }
      mine.put(file.number(), reader);
    }
    mine.values().forEach(SSTableReader::retain);
    this.files = List.copyOf(ordered);
    this.readers = Map.copyOf(mine);
  }

  /** The live tables, newest first. */
  public List<FileMetadata> files() {
    return files;
  }

  /** A reader per live table, newest first: the order a point lookup probes them in. */
  public List<SSTableReader> tablesNewestFirst() {
    return files.stream().map(file -> readers.get(file.number())).toList();
  }

  /** The reader of table {@code number}, or {@code null} if it is not in this Version. */
  public SSTableReader table(long number) {
    return readers.get(number);
  }

  /** The readers keyed by file number; a later Version is built from them. */
  Map<Long, SSTableReader> readers() {
    return readers;
  }

  @Override
  public void retain() {
    references.incrementAndGet();
  }

  /** Drops a reference; the last one releases every reader this Version retained. */
  @Override
  public void release() {
    int remaining = references.decrementAndGet();
    if (remaining < 0) {
      throw new IllegalStateException("Version released more often than retained");
    }
    if (remaining == 0) {
      StorageException first = null;
      for (SSTableReader reader : readers.values()) {
        try {
          reader.release();
        } catch (StorageException e) { // one failed delete must not leak the other readers
          if (first == null) {
            first = e;
          } else {
            first.addSuppressed(e);
          }
        }
      }
      if (first != null) {
        throw first;
      }
    }
  }
}
