package dev.shale.wal;

import dev.shale.internal.annotations.Immutable;

/**
 * What {@link WalReader} does with a torn record at the very tail of a segment — the normal result
 * of a crash mid-append. A bit-flip in a fully-present fragment is never covered by this: that is
 * always {@code CorruptionException} (N4). The caller chooses the policy; the reader never decides
 * on its own.
 *
 * <p><b>Known gap (ADR-0024; closed by M4.5 Steps 4–5).</b> The reader decides "torn payload" from
 * the {@code length} field, which the fragment CRC does not cover: a damaged length that runs past
 * the end of the file is read as a torn tail, and recovery drops acknowledged records silently
 * (SH-18). A {@code ZERO} type is accepted only as the start of an all-zero unwritten tail (SH-08).
 */
@Immutable
public enum RecoveryPolicy {
  /** Accept the clean prefix and drop the torn tail record. */
  TRUNCATE_TAIL,
  /** Treat a torn tail as corruption and refuse to continue. */
  STRICT
}
