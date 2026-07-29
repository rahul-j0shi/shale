package dev.shale.wal;

import dev.shale.internal.annotations.Immutable;

/**
 * What {@link WalReader} does with a torn record at the very tail of a segment — the normal result
 * of a crash mid-append. A bit-flip in a fully-present fragment is never covered by this: that is
 * always {@code CorruptionException} (N4). The caller chooses the policy; the reader never decides
 * on its own.
 */
@Immutable
public enum RecoveryPolicy {
  /** Accept the clean prefix and drop the torn tail record. */
  TRUNCATE_TAIL,
  /** Treat a torn tail as corruption and refuse to continue. */
  STRICT
}
