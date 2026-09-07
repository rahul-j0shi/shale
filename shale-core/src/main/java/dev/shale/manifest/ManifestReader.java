package dev.shale.manifest;

import dev.shale.CorruptionException;
import dev.shale.internal.annotations.ThreadSafe;
import dev.shale.internal.blocklog.BlockLogFormat;
import dev.shale.internal.blocklog.BlockLogReader;
import java.io.IOException;
import java.nio.file.Files;
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
 * always {@link CorruptionException} (N4).
 *
 * <p><b>Known residual risk.</b> A fragment's length field is not covered by its own checksum — the
 * length is what tells the reader where the checksummed bytes end — so a bit flipped there makes a
 * record claim more bytes than the file holds, and that is indistinguishable from a crash
 * mid-append. A flip in the *first* record is caught, because a manifest reachable through {@code
 * CURRENT} must hold at least one edit. A flip in a later record silently truncates the edit
 * history, which would present as an older version of the database rather than as an error. The
 * defence against that is outside this class: the file set a manifest describes is cross-checked
 * against the directory, so a table that recovery expects and cannot find is corruption.
 *
 * <p><b>Threading:</b> stateless.
 */
@ThreadSafe
public final class ManifestReader {

  private ManifestReader() {}

  /**
   * Reads every complete edit in {@code path}, in append order.
   *
   * @throws dev.shale.CorruptionException if the file holds no readable edit at all — see the note
   *     on truncation below
   */
  public static List<VersionEdit> readAll(Path path) throws IOException {
    List<byte[]> payloads =
        BlockLogReader.readAll(path, ManifestFormat.BLOCK_LOG, /* strictTail= */ false);
    long size = Files.size(path);
    if (payloads.isEmpty() && size > BlockLogFormat.FILE_HEADER_SIZE) {
      // A manifest with bytes but no readable edit is corruption, not an empty database. The
      // install protocol makes this decidable: CURRENT is only renamed onto a manifest after that
      // manifest's first edit is durable, so a manifest anyone can reach has at least one. Without
      // this check a single flipped bit in the first record's *length* field — which the CRC
      // cannot protect, because the length is what tells us where the CRC's data ends — makes the
      // whole file look torn, and the engine would open as if the database had no files at all.
      throw new CorruptionException(
          "manifest holds no readable edit", BlockLogFormat.FILE_HEADER_SIZE, -1, size);
    }
    List<VersionEdit> edits = new ArrayList<>(payloads.size());
    for (byte[] payload : payloads) {
      edits.add(VersionEdit.decode(payload));
    }
    return List.copyOf(edits);
  }
}
