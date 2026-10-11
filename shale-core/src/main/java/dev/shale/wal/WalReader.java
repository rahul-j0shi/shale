package dev.shale.wal;

import dev.shale.CorruptionException;
import dev.shale.env.Env;
import dev.shale.internal.annotations.ThreadSafe;
import dev.shale.internal.log.LogFormat;
import dev.shale.internal.log.LogReadResult;
import dev.shale.internal.log.LogReader;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Reads a WAL segment back into its logical record payloads. The framing — fragments, checksums,
 * reassembly, and how the file ended — is the shared log framing ({@link LogReader}); this class
 * checks the WAL's own file header and applies the caller's {@link RecoveryPolicy} to a torn tail.
 * Corruption is {@link CorruptionException} (N4) under either policy.
 *
 * <p><b>Segments of version 2</b> (written from M4.5) check each fragment header before using its
 * {@code length} to call a tail torn, so a damaged header is corruption. <b>Segments of version
 * 1</b> cannot check it, so a complete v1 header with a short payload is refused as unverifiable
 * rather than dropped (SH-18; see {@link #read}). In both, a {@code ZERO} type is accepted only as
 * the start of an all-zero unwritten tail (SH-08). ADR-0024 records the rules.
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
    return readAll(Env.DEFAULT, path, policy);
  }

  /** As {@link #readAll(Path, RecoveryPolicy)}, through {@code env}. */
  public static List<byte[]> readAll(Env env, Path path, RecoveryPolicy policy) throws IOException {
    LogReadResult result = read(env, path);
    if (result.tail() != LogReadResult.Tail.CLEAN && policy == RecoveryPolicy.STRICT) {
      throw new CorruptionException(
          "torn record at WAL tail of " + path.getFileName(), result.tailOffset(), -1, -1);
    }
    return result.records();
  }

  /**
   * Reads {@code path} and reports how it ended, applying no policy to a torn tail: the engine
   * decides, because only the newest segment may be torn (ADR-0024 §2). Corruption, and a v1 tail
   * that cannot be verified, throw.
   *
   * <ul>
   *   <li>An empty file is a clean, empty segment.
   *   <li>A file shorter than its 16-byte header is {@code TORN_FILE_HEADER}: a crash while the
   *       segment was being created, before any record could be acknowledged.
   *   <li>A <b>v1</b> segment whose last fragment header is complete but whose payload runs past
   *       the end of the file is <b>unverifiable</b>: v1 cannot tell a torn append from a damaged
   *       length (SH-18), so it is {@link CorruptionException} under every policy, naming the
   *       offset an operator who accepts the loss would truncate to (ADR-0024 §3).
   * </ul>
   */
  public static LogReadResult read(Path path) throws IOException {
    return read(Env.DEFAULT, path);
  }

  /** As {@link #read(Path)}, through {@code env}. */
  public static LogReadResult read(Env env, Path path) throws IOException {
    return read(path.getFileName().toString(), env.readAllBytes(path));
  }

  /** {@link #read(Path)} over bytes already in memory; {@code name} appears in error messages. */
  static LogReadResult read(String name, byte[] data) {
    if (data.length < WalFormat.FILE_HEADER_SIZE) {
      LogReadResult.Tail tail =
          data.length == 0 ? LogReadResult.Tail.CLEAN : LogReadResult.Tail.TORN_FILE_HEADER;
      return new LogReadResult(WalFormat.FORMAT_VERSION, List.of(), 0, tail, 0);
    }
    LogReadResult result =
        LogReader.read(data, WalFormat.MAGIC, WalFormat.READABLE_VERSIONS, "WAL");
    if (result.version() == LogFormat.VERSION_1
        && result.tail() == LogReadResult.Tail.TORN_PAYLOAD) {
      throw unverifiableTail(name, data, result);
    }
    return result;
  }

  private static CorruptionException unverifiableTail(
      String name, byte[] data, LogReadResult result) {
    int fragment = result.tailOffset();
    int claimed = (data[fragment + 4] & 0xFF) | ((data[fragment + 5] & 0xFF) << 8);
    int present = data.length - fragment - LogFormat.fragmentHeaderSize(LogFormat.VERSION_1);
    return new CorruptionException(
        "unverifiable WAL tail in "
            + name
            + " at offset "
            + fragment
            + ": header claims "
            + claimed
            + " payload bytes, "
            + present
            + " present; v1 cannot tell a torn append from a damaged length — to accept losing"
            + " this tail, truncate the file to "
            + result.endOffset()
            + " bytes and reopen",
        fragment,
        claimed,
        present);
  }
}
