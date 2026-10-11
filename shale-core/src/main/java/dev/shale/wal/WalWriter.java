package dev.shale.wal;

import dev.shale.Clock;
import dev.shale.Durability;
import dev.shale.Metrics;
import dev.shale.internal.annotations.NotThreadSafe;
import dev.shale.internal.log.LogWriter;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Appends logical records to one WAL segment. The framing — fragments across 32 KiB blocks — is the
 * shared log framing ({@link LogWriter}); this class owns the WAL's file header and its durability
 * contract (ADR-0008): {@code NONE} writes to the page cache; {@code SYNC} and {@code GROUP} force
 * before returning.
 *
 * <p><b>Threading:</b> single-writer; owned by the engine's write path and serialised by it.
 * <b>Ownership:</b> owns a {@link LogWriter}, closed by {@link #close()}.
 *
 * @see <a href="https://github.com/google/leveldb/blob/main/db/log_writer.cc">LevelDB
 *     log_writer.cc</a>
 */
@NotThreadSafe
public final class WalWriter implements AutoCloseable {

  private final LogWriter log;
  private final Clock clock;
  private final Metrics metrics;

  private WalWriter(LogWriter log, Clock clock, Metrics metrics) {
    this.log = log;
    this.clock = clock;
    this.metrics = metrics;
  }

  /** Opens a fresh segment at {@code path} (which must not exist) and writes its file header. */
  public static WalWriter open(Path path, Clock clock, Metrics metrics) throws IOException {
    return new WalWriter(
        LogWriter.create(path, WalFormat.MAGIC, WalFormat.FORMAT_VERSION), clock, metrics);
  }

  /** Appends one logical record, then satisfies {@code durability} before returning. */
  public void append(byte[] payload, Durability durability) throws IOException {
    log.append(payload);
    metrics.increment("wal.append.count", 1);
    if (durability != Durability.NONE) {
      long start = clock.nanoTime();
      log.sync(); // the DURABILITY point is LogWriter.sync's force
      metrics.increment("wal.sync.count", 1);
      metrics.record("wal.sync.duration", clock.nanoTime() - start);
    }
  }

  @Override
  public void close() throws IOException {
    log.close();
  }
}
