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
  /** fsync'd before returning; survives power loss. */
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
