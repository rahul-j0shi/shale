package dev.shale.manifest;

import dev.shale.internal.annotations.NotThreadSafe;
import dev.shale.internal.blocklog.BlockLogWriter;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Appends version edits to one manifest file (ADR-0012).
 *
 * <p>Every append is forced before it returns, and that is the engine's commit point for a version
 * install: until the edit is on stable storage the new version does not exist, and a crash leaves
 * the previous one intact. There is no {@code Durability} choice here as there is for the WAL — an
 * unforced manifest edit would mean a database whose file set disagrees with its files.
 *
 * <p><b>Threading:</b> single-writer; owned by the version-install path and serialised by it.
 * <b>Ownership:</b> holds the {@link BlockLogWriter} closed by {@link #close()}.
 */
@NotThreadSafe
public final class ManifestWriter implements AutoCloseable {

  private final BlockLogWriter log;

  private ManifestWriter(BlockLogWriter log) {
    this.log = log;
  }

  /** Opens a fresh manifest at {@code path}, which must not exist, and writes its header. */
  public static ManifestWriter open(Path path) throws IOException {
    return new ManifestWriter(BlockLogWriter.open(path, ManifestFormat.BLOCK_LOG));
  }

  /**
   * Appends one edit and forces it. On return, the edit is durable and the version is installed.
   */
  public void append(VersionEdit edit) throws IOException {
    log.append(edit.encode());
    // DURABILITY: the install commits here — see BlockLogWriter.force.
    log.force();
  }

  @Override
  public void close() throws IOException {
    log.close();
  }
}
