package dev.shale.iterator;

import dev.shale.Cursor;
import dev.shale.KeyComparator;
import dev.shale.internal.annotations.NotThreadSafe;
import dev.shale.internal.key.InternalKey;
import dev.shale.internal.key.ValueType;

/**
 * Turns a merged internal-key stream into the user-visible answer, applying the three rules of LSM
 * reconciliation (ADR-0011):
 *
 * <ol>
 *   <li><b>Newest wins.</b> Within a user key, sequence numbers descend (ADR-0004), so the first
 *       entry the merge yields for a key is its newest version. Every later entry for that same
 *       user key is a superseded version and is skipped.
 *   <li><b>Tombstones hide.</b> If that newest version is a delete, the key is absent — not just
 *       the tombstone but every older value behind it, which rule 1 has already arranged to skip.
 *   <li><b>The upper bound stops the scan.</b> User keys ascend, so the first key at or past {@code
 *       toExclusive} ends iteration rather than being filtered out of a continuing walk.
 * </ol>
 *
 * <p><b>Source order is irrelevant.</b> The cursor never asks which source an entry came from.
 * Precedence is carried entirely by the sequence number inside the key, so a newer version wins
 * even when it happens to live in an older source. That is what makes the rule safe as the LSM
 * grows more places to look — a memtable, a queue of immutable memtables, tables at many levels.
 *
 * <p><b>Ownership (N6).</b> The cursor <em>adopts</em> one reference — from M5, to the {@code
 * Version} whose tables it reads — and the merged stream: it does not retain again, and {@link
 * #close()} releases the reference and closes the merge. Ownership passes when the constructor is
 * called, so if positioning the cursor throws (a damaged block, say), the constructor gives both
 * back before rethrowing: until M5 a cursor that failed there kept its tables for good (SH-10). A
 * streaming cursor reads live file handles for its whole life, so <b>closing it is mandatory</b>:
 * an unclosed cursor pins files, and that pin is the only thing standing between a long scan and a
 * table the manifest has already removed.
 *
 * <p><b>Threading:</b> {@code @NotThreadSafe}; one owning thread.
 *
 * @see <a href="https://github.com/google/leveldb/blob/main/db/db_iter.cc">LevelDB db_iter.cc</a>
 * @see "Petrov, Database Internals, ch. 7 — reconciliation"
 */
@NotThreadSafe
public final class ReconcilingCursor implements Cursor {

  private final InternalIterator merged;
  private final KeyComparator userComparator;
  private final byte[] toExclusive;
  private final ReferenceCounted pinned;

  /** The user key under the cursor, or null when exhausted or closed. Single-thread owned. */
  private byte[] currentKey;

  /** The value under the cursor; meaningless unless {@link #currentKey} is set. */
  private byte[] currentValue;

  /** The last user key resolved, so its superseded versions can be skipped. */
  private byte[] resolvedUserKey;

  private boolean closed;

  /**
   * Positions a cursor at the first live key ≥ {@code fromInclusive}, adopting {@code merged} and
   * {@code pinned}: both are this cursor's from the call on, and released by {@link #close()} — or
   * by this constructor, if positioning throws.
   *
   * @param merged the merged stream over every source
   * @param userComparator orders user keys, for deduplication and the upper bound
   * @param fromInclusive lower bound, or null to start at the first key
   * @param toExclusive upper bound, or null to run to the end
   * @param pinned a reference already taken on what the sources read, or null if they pin nothing
   */
  public ReconcilingCursor(
      InternalIterator merged,
      KeyComparator userComparator,
      byte[] fromInclusive,
      byte[] toExclusive,
      ReferenceCounted pinned) {
    this.merged = merged;
    this.userComparator = userComparator;
    this.toExclusive = toExclusive == null ? null : toExclusive.clone();
    this.pinned = pinned;

    boolean positioned = false;
    try {
      // The lower bound is a seek, not a filter: every source skips straight to it. Seeking at the
      // maximal trailer lands before every version of the bound key, so an inclusive bound stays
      // inclusive (ADR-0004).
      if (fromInclusive == null) {
        merged.seekToFirst();
      } else {
        merged.seek(
            new InternalKey(fromInclusive, InternalKey.MAX_SEQUENCE, ValueType.FOR_SEEK).encode());
      }
      advanceToNextLiveKey();
      positioned = true;
    } finally {
      if (!positioned) {
        close(); // the caller never gets a cursor to close: give back what was adopted (SH-10)
      }
    }
  }

  @Override
  public boolean isValid() {
    return currentKey != null;
  }

  @Override
  public void next() {
    merged.next();
    advanceToNextLiveKey();
  }

  @Override
  public byte[] key() {
    requirePositioned();
    return currentKey;
  }

  @Override
  public byte[] value() {
    requirePositioned();
    return currentValue;
  }

  @Override
  public void close() {
    if (closed) {
      return; // releasing twice would free a table another reader still holds
    }
    closed = true;
    currentKey = null;
    currentValue = null;
    try {
      merged.close();
    } finally {
      if (pinned != null) {
        pinned.release();
      }
    }
  }

  /**
   * Walks the merged stream until it reaches a key the reader should see, or the scan ends.
   *
   * <p>Three things can stand in the way, and each is skipped for a different reason: a superseded
   * version of the key just returned, a tombstone (whose own older versions rule 1 then skips), and
   * the upper bound, which ends the scan outright because keys only ascend from here.
   */
  private void advanceToNextLiveKey() {
    while (merged.valid()) {
      InternalKey key = InternalKey.decode(merged.internalKey());
      byte[] userKey = key.userKey();

      if (resolvedUserKey != null && userComparator.compare(userKey, resolvedUserKey) == 0) {
        merged.next(); // an older version of a key already decided
        continue;
      }
      if (toExclusive != null && userComparator.compare(userKey, toExclusive) >= 0) {
        break; // past the range, and everything after it is too
      }

      resolvedUserKey = userKey;
      if (key.valueType() == ValueType.DELETE) {
        merged.next(); // deleted: hide this key and everything older behind it
        continue;
      }

      // Copy out. An InternalIterator's arrays belong to it and may not outlive the next
      // advance, but Cursor hands its bytes to a caller who is entitled to keep them — as the
      // materialising cursor this replaced did. One copy per *returned* entry is proportional to
      // the result, which is the cost model this milestone was about restoring.
      currentKey = userKey.clone();
      currentValue = merged.value().clone();
      return;
    }
    currentKey = null;
    currentValue = null;
  }

  private void requirePositioned() {
    if (currentKey == null) {
      throw new IllegalStateException(
          closed ? "cursor is closed" : "cursor is exhausted; check isValid() first");
    }
  }
}
