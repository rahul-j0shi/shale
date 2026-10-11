package dev.shale.wal;

import dev.shale.Clock;
import dev.shale.Durability;
import dev.shale.Metrics;
import dev.shale.env.Env;
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
    return open(Env.DEFAULT, path, clock, metrics);
  }

  /** As {@link #open(Path, Clock, Metrics)}, through {@code env}. */
  public static WalWriter open(Env env, Path path, Clock clock, Metrics metrics)
      throws IOException {
    return new WalWriter(
        LogWriter.create(env, path, WalFormat.MAGIC, WalFormat.FORMAT_VERSION), clock, metrics);
  }

  /** Appends one logical record, then satisfies {@code durability} before returning. */
  public void append(byte[] payload, Durability durability) throws IOException {
    log.append(payload);
    metrics.increment("wal.append.count", 1);
    if (durability != Durability.NONE) {
      sync();
    }
  }

  /**
   * Syncs the segment, then closes it. A closed segment is therefore complete on disk, so recovery
   * can require every segment but the newest to end cleanly: only the newest can have been cut
   * short by a crash (ADR-0024 §5). It costs one fsync per memtable switch. Without it, a power
   * loss after a switch could lose {@code NONE} writes in this segment while {@code SYNC} writes in
   * the next one survive, and recovery would no longer restore a prefix of the writes.
   */
  @Override
  public void close() throws IOException {
    try {
      // DURABILITY: a closed segment is complete, so only the newest can be torn (ADR-0024 §5).
      sync();
    } finally {
      log.close();
    }
  }

  private void sync() throws IOException {
    long start = clock.nanoTime();
    log.sync(); // the force itself, and its DURABILITY comment, are in LogWriter.sync
    metrics.increment("wal.sync.count", 1);
    metrics.record("wal.sync.duration", clock.nanoTime() - start);
  }
}
