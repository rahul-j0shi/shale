package dev.shale.manifest;

import dev.shale.internal.annotations.ThreadSafe;
import dev.shale.internal.blocklog.BlockLogReader;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Replays a manifest into the version edits it holds, in append order (ADR-0012). Folding them in
 * order yields the live file set.
 *
 * <p>A torn edit at the tail is <b>discarded, never reported</b> — unlike the WAL, the manifest has
 * no policy choice to offer. An edit is only partly on disk if the process died during the append
 * that installs it, which means the install never completed and the version it described was never
 * observed by anyone. Discarding it is not data loss; refusing to open because of it would make an
 * ordinary crash unrecoverable.
 *
 * <p>Corruption anywhere else — a CRC mismatch, a fragment out of sequence, an unknown tag — is
 * always {@link dev.shale.CorruptionException} (N4).
 *
 * <p><b>Threading:</b> stateless.
 */
@ThreadSafe
public final class ManifestReader {

  private ManifestReader() {}

  /** Reads every complete edit in {@code path}, in append order. */
  public static List<VersionEdit> readAll(Path path) throws IOException {
    List<byte[]> payloads =
        BlockLogReader.readAll(path, ManifestFormat.BLOCK_LOG, /* strictTail= */ false);
    List<VersionEdit> edits = new ArrayList<>(payloads.size());
    for (byte[] payload : payloads) {
      edits.add(VersionEdit.decode(payload));
    }
    return List.copyOf(edits);
  }
}
