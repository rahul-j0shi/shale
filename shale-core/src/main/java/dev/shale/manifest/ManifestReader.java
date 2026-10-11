package dev.shale.manifest;

import dev.shale.CorruptionException;
import dev.shale.env.Env;
import dev.shale.internal.annotations.ThreadSafe;
import dev.shale.internal.log.LogFormat;
import dev.shale.internal.log.LogReadResult;
import dev.shale.internal.log.LogReader;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Reads a manifest back into its version edits, in order ({@code format.md} §4). The framing
 * classifies how the file ends; the manifest's rule is that a torn record at the very end was never
 * synced, so it never took effect, and is dropped. Everything else that is wrong is corruption.
 *
 * <p><b>Threading:</b> stateless; each call reads one file.
 *
 * @see <a href="https://github.com/google/leveldb/blob/main/db/version_set.cc">LevelDB
 *     version_set.cc (Recover)</a>
 */
@ThreadSafe
public final class ManifestReader {

  private ManifestReader() {}

  /** The edits in {@code path}, oldest first. */
  public static List<VersionEdit> read(Env env, Path path) throws IOException {
    byte[] data = env.readAllBytes(path);
    try {
      return decode(data);
    } catch (CorruptionException e) {
      throw e.inFile(path);
    }
  }

  /** {@link #read} over bytes already in memory. */
  static List<VersionEdit> decode(byte[] data) {
    if (data.length < LogFormat.FILE_HEADER_SIZE) {
      // CURRENT names a manifest only after its header and first edit are synced.
      throw new CorruptionException(
          "manifest shorter than its file header", 0, LogFormat.FILE_HEADER_SIZE, data.length);
    }
    LogReadResult result =
        LogReader.read(
            data, ManifestFormat.MAGIC, Set.of(ManifestFormat.FORMAT_VERSION), "manifest");
    // Any tail but CLEAN is a torn final record: never synced, never in effect, dropped.
    List<VersionEdit> edits = new ArrayList<>(result.records().size());
    for (byte[] record : result.records()) {
      edits.add(VersionEditCodec.decode(record));
    }
    return edits;
  }
}
