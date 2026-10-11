package dev.shale.wal;

import dev.shale.internal.annotations.Immutable;

/**
 * What {@link WalReader} does with a torn record at the very tail of a segment — the normal result
 * of a crash mid-append. A bit-flip in a fully-present fragment is never covered by this: that is
 * always {@code CorruptionException} (N4). The caller chooses the policy; the reader never decides
 * on its own.
 *
 * <p>What counts as a torn tail depends on what the reader can verify (ADR-0024). In a version 2
 * segment the fragment header is checksummed, so a short payload is a genuine torn append. In a
 * version 1 segment it is not, so a complete header with a short payload is refused as unverifiable
 * under <em>both</em> policies, rather than dropped (SH-18). The engine also accepts a torn tail
 * only in the newest segment it replays.
 */
@Immutable
public enum RecoveryPolicy {
  /** Accept the clean prefix and drop the torn tail record. */
  TRUNCATE_TAIL,
  /** Treat a torn tail as corruption and refuse to continue. */
  STRICT
}
