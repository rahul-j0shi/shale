package dev.shale.wal;

import dev.shale.Clock;
import dev.shale.Durability;
import dev.shale.Metrics;
import dev.shale.internal.annotations.NotThreadSafe;
import dev.shale.internal.blocklog.BlockLogWriter;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Appends mutations to one WAL segment, and decides when they become durable.
 *
 * <p>The framing — fragments across 32 KiB blocks, CRC32C per fragment (ADR-0007) — belongs to
 * {@link BlockLogWriter} and is shared with the manifest. What is WAL-specific, and lives here, is
 * the durability policy (ADR-0008): {@code NONE} writes to the page cache and returns; {@code SYNC}
 * and {@code GROUP} force before returning. So does the measurement of how long that costs.
 *
 * <p><b>Threading:</b> single-writer; owned by the engine's write path and serialised by it.
 * <b>Ownership:</b> holds the {@link BlockLogWriter} closed by {@link #close()}.
 */
@NotThreadSafe
public final class WalWriter implements AutoCloseable {

  private final BlockLogWriter log;
  private final Clock clock;
  private final Metrics metrics;

  private WalWriter(BlockLogWriter log, Clock clock, Metrics metrics) {
    this.log = log;
    this.clock = clock;
    this.metrics = metrics;
  }

  /** Opens a fresh segment at {@code path} (which must not exist) and writes its file header. */
  public static WalWriter open(Path path, Clock clock, Metrics metrics) throws IOException {
    return new WalWriter(BlockLogWriter.open(path, WalFormat.BLOCK_LOG), clock, metrics);
  }

  /** Appends one mutation, then satisfies {@code durability} before returning. */
  public void append(byte[] payload, Durability durability) throws IOException {
    log.append(payload);
    metrics.increment("wal.append.count", 1);
    if (durability != Durability.NONE) {
      sync();
    }
  }

  private void sync() throws IOException {
    long start = clock.nanoTime();
    log.force(); // DURABILITY: see BlockLogWriter.force — the write survives power loss after it
    metrics.increment("wal.sync.count", 1);
    metrics.record("wal.sync.duration", clock.nanoTime() - start);
  }

  @Override
  public void close() throws IOException {
    log.close();
  }
}
