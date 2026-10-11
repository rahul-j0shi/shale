package dev.shale.wal;

import dev.shale.internal.annotations.Immutable;

/**
 * What {@link WalReader} does with a torn record at the very tail of a segment — the normal result
 * of a crash mid-append. A bit-flip in a fully-present fragment is never covered by this: that is
 * always {@code CorruptionException} (N4). The caller chooses the policy; the reader never decides
 * on its own.
 *
 * <p><b>Segments of version 2</b> (written from M4.5) check each fragment header before using its
 * {@code length} to call a tail torn, so a damaged header is corruption (ADR-0024). <b>Known gap in
 * version 1 segments (closed by M4.5 Step 5):</b> their {@code length} is unchecked, so a damaged
 * length that runs past the end of the file still reads as a torn tail (SH-18). In both, a {@code
 * ZERO} type is accepted only as the start of an all-zero unwritten tail (SH-08).
 */
@Immutable
public enum RecoveryPolicy {
  /** Accept the clean prefix and drop the torn tail record. */
  TRUNCATE_TAIL,
  /** Treat a torn tail as corruption and refuse to continue. */
  STRICT
}
