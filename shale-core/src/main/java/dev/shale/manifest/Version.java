package dev.shale.manifest;

import dev.shale.EngineStateException;
import dev.shale.internal.annotations.ThreadSafe;
import dev.shale.iterator.ReferenceCounted;
import dev.shale.sstable.SSTableReader;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One immutable snapshot of the live file set (ADR-0012). Installing an edit does not mutate a
 * version; it builds the next one, and the old one is released.
 *
 * <p>A version <b>owns</b> the tables it lists: it retains each on construction and releases each
 * when its own last reference goes. That is what makes a table's lifetime the union of the versions
 * and the cursors that need it, rather than a guess. A reader pins the version it reads from, so a
 * scan that started before an install keeps a coherent file set underneath it even as compaction
 * (M6) replaces every table in it.
 *
 * <p>Combined with {@link SSTableReader#markObsolete()}, this is delete-on-zero: a table dropped
 * from the live set is deleted by whichever reference goes last — the superseded version, or the
 * cursor that outlived it.
 *
 * <p><b>Threading:</b> immutable except for the reference count, which is atomic. Readers take a
 * reference under no lock.
 */
@ThreadSafe
public final class Version implements ReferenceCounted {

  private final List<SSTableReader> tablesNewestFirst;
  private final AtomicInteger references = new AtomicInteger(1);

  /**
   * A version over {@code tablesNewestFirst}, newest table first — the order a point lookup probes,
   * so the first table holding a key holds its newest version.
   *
   * <p>Retains every table: the caller keeps its own references and releases them independently.
   */
  public Version(List<SSTableReader> tablesNewestFirst) {
    this.tablesNewestFirst = List.copyOf(tablesNewestFirst);
    for (SSTableReader table : this.tablesNewestFirst) {
      table.retain();
    }
  }

  /** The live tables, newest first. Valid only while a reference to this version is held. */
  public List<SSTableReader> tablesNewestFirst() {
    return tablesNewestFirst;
  }

  /** How many tables are live in this version. */
  public int tableCount() {
    return tablesNewestFirst.size();
  }

  /** Pins this version; pair with {@link #release}. */
  @Override
  public void retain() {
    if (references.getAndIncrement() <= 0) {
      throw new EngineStateException("retained a version that was already released");
    }
  }

  /** Drops a reference; the last one releases every table this version held. */
  @Override
  public void release() {
    int remaining = references.decrementAndGet();
    if (remaining < 0) {
      // Not a data problem — an accounting bug in the engine, and the kind that otherwise shows up
      // much later as a closed channel or a missing file (N6).
      throw new EngineStateException("version released more times than it was retained");
    }
    if (remaining == 0) {
      for (SSTableReader table : tablesNewestFirst) {
        table.release();
      }
    }
  }
}
