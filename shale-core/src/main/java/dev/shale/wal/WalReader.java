package dev.shale.wal;

import dev.shale.CorruptionException;
import dev.shale.internal.annotations.ThreadSafe;
import dev.shale.internal.coding.Crc32c;
import dev.shale.internal.coding.LittleEndian;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Reads a WAL segment back into its logical record payloads, verifying every fragment's CRC and
 * reassembling records that span blocks (ADR-0007). A byte error in a fully-present fragment is
 * {@link CorruptionException} (N4); a truncated tail — the normal result of a crash mid-append — is
 * handled per the caller's {@link RecoveryPolicy}.
 *
 * <p><b>Known gap (ADR-0024; closed by M4.5 Steps 4–5).</b> The reader decides "torn payload" from
 * the {@code length} field, which the fragment CRC does not cover: a damaged length that runs past
 * the end of the file is read as a torn tail, and recovery drops acknowledged records silently
 * (SH-18). A {@code ZERO} type is accepted only as the start of an all-zero unwritten tail (SH-08).
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
    verifyHeader(data);
    return parse(data, policy);
  }

  private static void verifyHeader(byte[] data) {
    long magic = LittleEndian.getFixed64(data, 0);
    if (magic != WalFormat.MAGIC) {
      throw new CorruptionException("bad WAL magic", 0, WalFormat.MAGIC, magic);
    }
    int version = LittleEndian.getFixed32(data, 8);
    if (version != WalFormat.FORMAT_VERSION) {
      throw new CorruptionException(
          "unsupported WAL version", 8, WalFormat.FORMAT_VERSION, version);
    }
    int reserved = LittleEndian.getFixed32(data, 12);
    if (reserved != 0) {
      throw new CorruptionException("non-zero reserved WAL header bytes", 12, 0, reserved);
    }
  }

  private static List<byte[]> parse(byte[] data, RecoveryPolicy policy) {
    Assembler assembler = new Assembler();
    boolean torn = false;
    int pos = WalFormat.FILE_HEADER_SIZE;

    while (pos < data.length && !torn) {
      int blockRemaining =
          WalFormat.BLOCK_SIZE - ((pos - WalFormat.FILE_HEADER_SIZE) % WalFormat.BLOCK_SIZE);
      if (blockRemaining < WalFormat.FRAGMENT_HEADER_SIZE) {
        // The writer zero-pads a block tail too short for a header; padding is verified, never
        // trusted (on-disk-formats.md §2). Only the bytes present are checked: a file may end
        // inside the padding, which is an ordinary tail.
        requireZeroPadding(data, pos, Math.min(pos + blockRemaining, data.length));
        pos += blockRemaining;
        continue;
      }
      if (pos + WalFormat.FRAGMENT_HEADER_SIZE > data.length) {
        torn = true; // header truncated at tail
        continue;
      }
      int length = (data[pos + 4] & 0xFF) | ((data[pos + 5] & 0xFF) << 8);
      int typeCode = data[pos + 6] & 0xFF;
      if (typeCode == RecordType.ZERO.code()) {
        // The writer never writes a ZERO fragment header. One is legitimate only as the start of
        // an unwritten tail: space a filesystem allocated but never wrote, exposed as zeros after
        // a crash. Anything else is a damaged type byte, and skipping it as padding — before any
        // CRC check — is how one flipped bit once erased every record in a block (SH-08).
        if (!allZero(data, pos, data.length)) {
          throw new CorruptionException(
              "WAL fragment type ZERO outside an unwritten (all-zero) tail", pos + 6, 1, 0);
        }
        torn = true;
        continue;
      }
      if (length > blockRemaining - WalFormat.FRAGMENT_HEADER_SIZE) {
        throw new CorruptionException(
            "WAL fragment length overruns block", pos, blockRemaining, length);
      }
      int fragEnd = pos + WalFormat.FRAGMENT_HEADER_SIZE + length;
      if (fragEnd > data.length) {
        torn = true; // payload truncated at tail
        continue;
      }
      int storedCrc = LittleEndian.getFixed32(data, pos);
      int actualCrc = Crc32c.of(data, pos + 6, 1 + length);
      if (storedCrc != actualCrc) {
        throw new CorruptionException("WAL fragment CRC mismatch", pos, storedCrc, actualCrc);
      }
      byte[] fragment = Arrays.copyOfRange(data, pos + WalFormat.FRAGMENT_HEADER_SIZE, fragEnd);
      assembler.accept(RecordType.fromCode(typeCode), fragment, pos);
      pos = fragEnd;
    }

    if ((torn || assembler.inRecord()) && policy == RecoveryPolicy.STRICT) {
      throw new CorruptionException("torn record at WAL tail", pos, -1, -1);
    }
    return assembler.records();
  }

  private static void requireZeroPadding(byte[] data, int from, int to) {
    for (int i = from; i < to; i++) {
      if (data[i] != 0) {
        throw new CorruptionException("non-zero WAL block padding", i, 0, data[i] & 0xFF);
      }
    }
  }

  private static boolean allZero(byte[] data, int from, int to) {
    for (int i = from; i < to; i++) {
      if (data[i] != 0) {
        return false;
      }
    }
    return true;
  }

  /** Reassembles fragments into logical records, tracking the FIRST/MIDDLE/LAST state. */
  private static final class Assembler {
    private final List<byte[]> records = new ArrayList<>();
    private final ByteArrayOutputStream pending = new ByteArrayOutputStream();
    private boolean inRecord;

    void accept(RecordType type, byte[] fragment, int offset) {
      switch (type) {
        case FULL -> {
          require(!inRecord, offset);
          records.add(fragment);
        }
        case FIRST -> {
          require(!inRecord, offset);
          pending.reset();
          pending.writeBytes(fragment);
          inRecord = true;
        }
        case MIDDLE -> {
          require(inRecord, offset);
          pending.writeBytes(fragment);
        }
        case LAST -> {
          require(inRecord, offset);
          pending.writeBytes(fragment);
          records.add(pending.toByteArray());
          inRecord = false;
        }
        default ->
            throw new CorruptionException("unexpected WAL fragment type", offset, -1, type.code());
      }
    }

    private static void require(boolean ok, int offset) {
      if (!ok) {
        throw new CorruptionException("WAL fragment out of sequence", offset, -1, -1);
      }
    }

    boolean inRecord() {
      return inRecord;
    }

    List<byte[]> records() {
      return records;
    }
  }
}
