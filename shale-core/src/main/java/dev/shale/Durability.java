package dev.shale;

import dev.shale.internal.annotations.Immutable;

/**
 * The durability guarantee a write demands before it is acknowledged (N3,
 * concurrency-and-resources.md §5). No default: the caller always chooses.
 *
 * <p>At M0 the only {@link StorageBackend} is in-memory and non-durable, so it validates the
 * argument as non-null but cannot honour it; these modes gain meaning with the WAL (M1). Never
 * widen or silently narrow a guarantee (D4).
 */
@Immutable
public enum Durability {
  /** Buffered only; survives process crash, not power loss. */
  NONE,
  /** fsync'd before returning; survives power loss. */
  SYNC,
  /** Batched with concurrent writers into a shared fsync (group commit). */
  GROUP
}
