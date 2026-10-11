package dev.shale.wal;

import dev.shale.internal.annotations.Immutable;

/**
 * What {@link WalReader} does with a torn record at the very tail of a segment — the normal result
 * of a crash mid-append. A bit-flip in a fully-present fragment is never covered by this: that is
 * always {@code CorruptionException} (N4). The caller chooses the policy; the reader never decides
 * on its own.
 *
 * <p><b>Known gap (ADR-0024; closed by M4.5 Steps 3–5).</b> The reader decides "torn tail" from
 * fields the fragment CRC does not cover: a {@code ZERO} type skips the rest of a block before any
 * CRC check (SH-08), and a damaged {@code length} that runs past the end of the file is read as a
 * torn payload (SH-18). Either can make recovery drop acknowledged records silently.
 */
@Immutable
public enum RecoveryPolicy {
  /** Accept the clean prefix and drop the torn tail record. */
  TRUNCATE_TAIL,
  /** Treat a torn tail as corruption and refuse to continue. */
  STRICT
}
