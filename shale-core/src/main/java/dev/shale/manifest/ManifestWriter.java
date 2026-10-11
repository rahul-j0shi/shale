package dev.shale.manifest;

import dev.shale.env.Env;
import dev.shale.internal.annotations.NotThreadSafe;
import dev.shale.internal.log.LogWriter;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Appends version edits to one manifest ({@code format.md}), each synced before {@link #log}
 * returns: an edit takes effect exactly when its sync does.
 *
 * <p><b>Threading:</b> single-writer; owned by the {@link VersionSet}, which serialises edits.
 * <b>Ownership:</b> owns a {@link LogWriter}, closed by {@link #close()}.
 *
 * @see <a href="https://github.com/google/leveldb/blob/main/db/version_set.cc">LevelDB
 *     version_set.cc (LogAndApply)</a>
 */
@NotThreadSafe
public final class ManifestWriter implements AutoCloseable {

  private final LogWriter log;

  private ManifestWriter(LogWriter log) {
    this.log = log;
  }

  /** Creates manifest {@code path} (which must not exist) and writes its file header. */
  public static ManifestWriter create(Env env, Path path) throws IOException {
    return new ManifestWriter(
        LogWriter.create(env, path, ManifestFormat.MAGIC, ManifestFormat.FORMAT_VERSION));
  }

  /** Appends {@code edit} and syncs it. When this returns, the edit survives power loss. */
  public void log(VersionEdit edit) throws IOException {
    log.append(VersionEditCodec.encode(edit));
    // DURABILITY: the edit is durable when LogWriter.sync returns: this is the commit point of
    // every
    // flush, and of each new manifest's first, full-state edit (ADR-0012).
    log.sync();
  }

  /** The manifest's size so far, for the rollover decision. */
  public long sizeBytes() {
    return log.sizeBytes();
  }

  @Override
  public void close() throws IOException {
    log.close();
  }
}
