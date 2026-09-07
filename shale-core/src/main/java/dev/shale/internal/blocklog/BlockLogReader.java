package dev.shale.internal.blocklog;

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
 * Reads a block-log file back into the logical record payloads it holds, in append order
 * (ADR-0007).
 *
 * <p>Two failures are distinguished, and the distinction is the whole point of the format. A record
 * that is <em>incomplete at the tail</em> is a crash during an append: the write was never
 * acknowledged, so discarding it loses nothing, and {@code strictTail} decides whether the caller
 * wants that discarded or reported. Anything else — a CRC mismatch, a fragment out of sequence, a
 * length overrunning its block — is corruption of data that was successfully written, and is always
 * {@link CorruptionException} with its offset (N4). Never a skipped record.
 *
 * <p><b>Threading:</b> stateless.
 *
 * @see <a href="https://github.com/google/leveldb/blob/main/db/log_reader.cc">LevelDB
 *     log_reader.cc</a>
 */
@ThreadSafe
public final class BlockLogReader {

  private BlockLogReader() {}

  /**
   * Replays {@code path} into its logical record payloads.
   *
   * @param strictTail if true, a torn record at the tail throws instead of being discarded
   */
  public static List<byte[]> readAll(Path path, BlockLogFormat format, boolean strictTail)
      throws IOException {
    byte[] data = Files.readAllBytes(path);
    if (data.length == 0) {
      return List.of();
    }
    if (data.length < BlockLogFormat.FILE_HEADER_SIZE) {
      throw new CorruptionException(
          "truncated " + format.name() + " header",
          0,
          BlockLogFormat.FILE_HEADER_SIZE,
          data.length);
    }
    verifyHeader(data, format);
    return parse(data, format, strictTail);
  }

  private static void verifyHeader(byte[] data, BlockLogFormat format) {
    long magic = LittleEndian.getFixed64(data, 0);
    if (magic != format.magic()) {
      throw new CorruptionException("bad " + format.name() + " magic", 0, format.magic(), magic);
    }
    int version = LittleEndian.getFixed32(data, 8);
    if (version != format.formatVersion()) {
      throw new CorruptionException(
          "unsupported " + format.name() + " version", 8, format.formatVersion(), version);
    }
    int reserved = LittleEndian.getFixed32(data, 12);
    if (reserved != 0) {
      throw new CorruptionException(
          "non-zero reserved " + format.name() + " header bytes", 12, 0, reserved);
    }
  }

  private static List<byte[]> parse(byte[] data, BlockLogFormat format, boolean strictTail) {
    Assembler assembler = new Assembler(format.name());
    boolean torn = false;
    int pos = BlockLogFormat.FILE_HEADER_SIZE;

    while (pos < data.length && !torn) {
      int blockRemaining =
          BlockLogFormat.BLOCK_SIZE
              - ((pos - BlockLogFormat.FILE_HEADER_SIZE) % BlockLogFormat.BLOCK_SIZE);
      if (blockRemaining < BlockLogFormat.FRAGMENT_HEADER_SIZE) {
        pos += blockRemaining; // zero-padded block tail
        continue;
      }
      if (pos + BlockLogFormat.FRAGMENT_HEADER_SIZE > data.length) {
        torn = true; // header truncated at tail
        continue;
      }
      int length = (data[pos + 4] & 0xFF) | ((data[pos + 5] & 0xFF) << 8);
      int typeCode = data[pos + 6] & 0xFF;
      if (typeCode == FragmentType.ZERO.code()) {
        pos += blockRemaining; // padding to the next block
        continue;
      }
      if (length > blockRemaining - BlockLogFormat.FRAGMENT_HEADER_SIZE) {
        throw new CorruptionException(
            format.name() + " fragment length overruns block", pos, blockRemaining, length);
      }
      int fragEnd = pos + BlockLogFormat.FRAGMENT_HEADER_SIZE + length;
      if (fragEnd > data.length) {
        torn = true; // payload truncated at tail
        continue;
      }
      int storedCrc = LittleEndian.getFixed32(data, pos);
      int actualCrc = Crc32c.of(data, pos + 6, 1 + length);
      if (storedCrc != actualCrc) {
        throw new CorruptionException(
            format.name() + " fragment CRC mismatch", pos, storedCrc, actualCrc);
      }
      byte[] fragment =
          Arrays.copyOfRange(data, pos + BlockLogFormat.FRAGMENT_HEADER_SIZE, fragEnd);
      assembler.accept(FragmentType.fromCode(typeCode), fragment, pos);
      pos = fragEnd;
    }

    if ((torn || assembler.inRecord()) && strictTail) {
      throw new CorruptionException("torn record at " + format.name() + " tail", pos, -1, -1);
    }
    return assembler.records();
  }

  /** Reassembles fragments into logical records, tracking the FIRST/MIDDLE/LAST state. */
  private static final class Assembler {
    private final List<byte[]> records = new ArrayList<>();
    private final ByteArrayOutputStream pending = new ByteArrayOutputStream();
    private final String name;
    private boolean inRecord;

    Assembler(String name) {
      this.name = name;
    }

    void accept(FragmentType type, byte[] fragment, int offset) {
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
            throw new CorruptionException(
                "unexpected " + name + " fragment type", offset, -1, type.code());
      }
    }

    private void require(boolean ok, int offset) {
      if (!ok) {
        throw new CorruptionException(name + " fragment out of sequence", offset, -1, -1);
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
