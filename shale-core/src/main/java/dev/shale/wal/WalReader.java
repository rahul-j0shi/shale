package dev.shale.wal;

import dev.shale.internal.annotations.ThreadSafe;
import dev.shale.internal.blocklog.BlockLogReader;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Replays a WAL segment into the mutation payloads it holds, in append order (ADR-0007).
 *
 * <p>The parsing is {@link BlockLogReader}'s, shared with the manifest. What is WAL-specific is the
 * choice this type maps: a record torn at the tail is a crash mid-append, and {@link
 * RecoveryPolicy} decides whether recovery discards it or refuses to open. Corruption anywhere else
 * is always thrown (N4), whatever the policy.
 *
 * <p><b>Threading:</b> stateless.
 */
@ThreadSafe
public final class WalReader {

  private WalReader() {}

  /** Replays {@code path} into the logical record payloads it holds, in append order. */
  public static List<byte[]> readAll(Path path, RecoveryPolicy policy) throws IOException {
    return BlockLogReader.readAll(path, WalFormat.BLOCK_LOG, policy == RecoveryPolicy.STRICT);
  }
}
