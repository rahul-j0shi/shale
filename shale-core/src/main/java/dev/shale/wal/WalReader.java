package dev.shale.wal;

import dev.shale.CorruptionException;
import dev.shale.internal.annotations.ThreadSafe;
import dev.shale.internal.log.LogReadResult;
import dev.shale.internal.log.LogReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Reads a WAL segment back into its logical record payloads. The framing — fragments, checksums,
 * reassembly, and how the file ended — is the shared log framing ({@link LogReader}); this class
 * checks the WAL's own file header and applies the caller's {@link RecoveryPolicy} to a torn tail.
 * Corruption is {@link CorruptionException} (N4) under either policy.
 *
 * <p><b>Segments of version 2</b> (written from M4.5) check each fragment header before using its
 * {@code length} to call a tail torn, so a damaged header is corruption (ADR-0024). <b>Known gap in
 * version 1 segments (closed by M4.5 Step 5):</b> their {@code length} is unchecked, so a damaged
 * length that runs past the end of the file still reads as a torn tail (SH-18). In both, a {@code
 * ZERO} type is accepted only as the start of an all-zero unwritten tail (SH-08).
 *
 * <p><b>Threading:</b> stateless; each call reads one file.
 *
 * @see <a href="https://github.com/google/leveldb/blob/main/db/log_reader.cc">LevelDB
 *     log_reader.cc</a>
 */
@ThreadSafe
public final class WalReader {

  private WalReader() {}

  /** Replays {@code path} into the logical record payloads it holds, in append order. */
  public static List<byte[]> readAll(Path path, RecoveryPolicy policy) throws IOException {
    byte[] data = Files.readAllBytes(path);
    if (data.length == 0) {
      return List.of();
    }
    if (data.length < WalFormat.FILE_HEADER_SIZE) {
      throw new CorruptionException(
          "truncated WAL header", 0, WalFormat.FILE_HEADER_SIZE, data.length);
    }
    LogReadResult result =
        LogReader.read(data, WalFormat.MAGIC, WalFormat.READABLE_VERSIONS, "WAL");
    if (result.tail() != LogReadResult.Tail.CLEAN && policy == RecoveryPolicy.STRICT) {
      throw new CorruptionException("torn record at WAL tail", result.endOffset(), -1, -1);
    }
    return result.records();
  }
}
