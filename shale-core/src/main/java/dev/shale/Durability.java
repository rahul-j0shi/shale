package dev.shale;

import dev.shale.internal.annotations.Immutable;

/**
 * The durability guarantee a write demands before it is acknowledged (N3,
 * concurrency-and-resources.md §5). No default: the caller always chooses.
 *
 * <p>Never widen or silently narrow a guarantee (D4): a mode means exactly what it says here, and a
 * mode that cannot yet be honoured says so rather than pretending.
 */
@Immutable
public enum Durability {
  /** Buffered only; survives process crash, not power loss. */
  NONE,
  /**
   * The WAL record is fsync'd before returning; survives process crash.
   *
   * <p><b>Power loss: not yet guaranteed.</b> The record's bytes are forced, but the directory
   * entry of a newly created WAL segment, and of a newly flushed SSTable, is not synced, so a power
   * loss soon after a memtable switch or a flush can lose the file holding an acknowledged write.
   * M5 adds the directory syncs and tests them under simulated power loss (ADR-0024 §7).
   */
  SYNC,
  /**
   * Reserved for group commit: concurrent writers batched into one shared fsync.
   *
   * <p><b>Currently identical to {@link #SYNC}</b> — every write forces before it is acknowledged,
   * so the guarantee is honoured, but no batching happens and a caller gains nothing over {@code
   * SYNC}. The leader/follower mechanism ADR-0008 chose (option B2) requires the {@code force()} to
   * happen <em>outside</em> the write lock, which the current write path does not do; both land
   * together in the write-path restructure (M5.5).
   */
  GROUP
}
