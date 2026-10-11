package dev.shale.internal.log;

import dev.shale.CorruptionException;
import dev.shale.internal.annotations.ThreadSafe;
import dev.shale.internal.coding.Crc32c;
import dev.shale.internal.coding.LittleEndian;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * Reads one log file's bytes back into its logical records, and says how the file ended ({@code
 * format.md} §3). It verifies every fragment, reassembles records that span blocks, and classifies
 * the end of the file as {@link LogReadResult.Tail clean or torn}. Corruption — a checksum
 * mismatch, an impossible field, a fragment out of sequence, non-zero padding — is thrown as {@link
 * CorruptionException} (N4) and never classified.
 *
 * <p>It applies no recovery policy: accepting a torn tail, and where one may occur, are decisions
 * of the log that uses it (the WAL's {@code RecoveryPolicy}; ADR-0024 §2).
 *
 * <p><b>Threading:</b> stateless; each call reads one byte array, which it never modifies.
 *
 * @see <a href="https://github.com/google/leveldb/blob/main/db/log_reader.cc">LevelDB
 *     log_reader.cc</a>
 */
@ThreadSafe
public final class LogReader {

  private LogReader() {}

  /**
   * Reads a log whose file header must name {@code magic} and one of {@code readableVersions}.
   * {@code data} must hold at least the 16-byte file header; a shorter file is the caller's to
   * classify. {@code logName} ("WAL") prefixes every error message.
   */
  public static LogReadResult read(
      byte[] data, long magic, Set<Integer> readableVersions, String logName) {
    int version = verifyFileHeader(data, magic, readableVersions, logName);
    return new Parse(data, version, logName).run();
  }

  private static int verifyFileHeader(
      byte[] data, long magic, Set<Integer> readableVersions, String logName) {
    long actualMagic = LittleEndian.getFixed64(data, 0);
    if (actualMagic != magic) {
      throw new CorruptionException("bad " + logName + " magic", 0, magic, actualMagic);
    }
    int version = LittleEndian.getFixed32(data, 8);
    if (!readableVersions.contains(version)) {
      throw new CorruptionException("unsupported " + logName + " version", 8, -1, version);
    }
    int reserved = LittleEndian.getFixed32(data, 12);
    if (reserved != 0) {
      throw new CorruptionException(
          "non-zero reserved " + logName + " header bytes", 12, 0, reserved);
    }
    return version;
  }

  /** One pass over one file: the position, the assembler, and where the last record ended. */
  private static final class Parse {
    private final byte[] data;
    private final int version;
    private final int headerSize;
    private final String logName;
    private final List<byte[]> records = new ArrayList<>();
    private final ByteArrayOutputStream pending = new ByteArrayOutputStream();
    private boolean inRecord;
    private int pos = LogFormat.FILE_HEADER_SIZE;
    private int endOffset = LogFormat.FILE_HEADER_SIZE;

    Parse(byte[] data, int version, String logName) {
      this.data = data;
      this.version = version;
      this.headerSize = LogFormat.fragmentHeaderSize(version);
      this.logName = logName;
    }

    LogReadResult run() {
      LogReadResult.Tail tail = null;
      while (pos < data.length && tail == null) {
        tail = step();
      }
      if (tail == null) {
        tail = inRecord ? LogReadResult.Tail.OPEN_RECORD : LogReadResult.Tail.CLEAN;
      }
      return new LogReadResult(version, List.copyOf(records), endOffset, tail);
    }

    /** Consumes one fragment or one padding run; returns a tail if the file ends torn here. */
    private LogReadResult.Tail step() {
      int blockRemaining =
          LogFormat.BLOCK_SIZE - ((pos - LogFormat.FILE_HEADER_SIZE) % LogFormat.BLOCK_SIZE);
      if (blockRemaining < headerSize) {
        // The writer zero-pads a block tail too short for a header. Padding is verified, never
        // trusted (on-disk-formats.md §2); a file may end inside it, so only present bytes count.
        requireZero(Math.min(pos + blockRemaining, data.length));
        pos += blockRemaining;
        return null;
      }
      return fragment(blockRemaining);
    }

    /** Consumes the fragment at {@code pos}, or classifies the torn tail that starts there. */
    private LogReadResult.Tail fragment(int blockRemaining) {
      if (data.length - pos < headerSize) {
        return LogReadResult.Tail.TORN_HEADER;
      }
      if (isZero(pos, pos + headerSize) && isZero(pos, data.length)) {
        // Space a filesystem allocated but never wrote reads back as zeros after a crash. Checked
        // only when the header itself is all zero, so a normal read stays linear.
        return LogReadResult.Tail.UNWRITTEN;
      }
      if (version >= LogFormat.VERSION_2) {
        verifyHeaderCrc();
      }
      int length = (data[pos + 4] & 0xFF) | ((data[pos + 5] & 0xFF) << 8);
      int typeCode = data[pos + 6] & 0xFF;
      if (typeCode == RecordType.ZERO.code()) {
        // The writer never writes a ZERO header; outside an all-zero tail it is a damaged type
        // byte. Skipping it as padding, before any CRC check, once erased a block (SH-08).
        throw new CorruptionException(
            logName + " fragment type ZERO outside an unwritten (all-zero) tail", pos + 6, 1, 0);
      }
      RecordType type = RecordType.fromCode(typeCode);
      if (length > blockRemaining - headerSize) {
        throw new CorruptionException(
            logName + " fragment length overruns block", pos, blockRemaining - headerSize, length);
      }
      int fragmentEnd = pos + headerSize + length;
      if (fragmentEnd > data.length) {
        // In v2 this length passed its header checksum, so a payload past the end of the file is a
        // genuine torn append. In v1 it is unverified, and may be a damaged length (SH-18).
        return LogReadResult.Tail.TORN_PAYLOAD;
      }
      int storedCrc = LittleEndian.getFixed32(data, pos);
      int actualCrc = Crc32c.of(data[pos + 6], data, pos + headerSize, length);
      if (storedCrc != actualCrc) {
        throw new CorruptionException(
            logName + " fragment CRC mismatch", pos, storedCrc, actualCrc);
      }
      accept(type, Arrays.copyOfRange(data, pos + headerSize, fragmentEnd));
      pos = fragmentEnd;
      if (!inRecord) {
        endOffset = pos;
      }
      return null;
    }

    /**
     * Checks a v2 fragment's {@code headerCrc} before any of its fields is used. A mismatch is
     * corruption wherever it occurs, the last fragment included: a crash leaves a byte-prefix of
     * what was written, and a prefix long enough to hold the whole header holds its checksum too.
     */
    private void verifyHeaderCrc() {
      int stored = LittleEndian.getFixed32(data, pos + LogFormat.CHECKED_HEADER_BYTES);
      int actual = Crc32c.of(data, pos, LogFormat.CHECKED_HEADER_BYTES);
      if (stored != actual) {
        throw new CorruptionException(
            logName + " fragment header CRC mismatch", pos, stored, actual);
      }
    }

    /** Reassembles fragments, tracking the FIRST/MIDDLE/LAST state. */
    private void accept(RecordType type, byte[] fragment) {
      switch (type) {
        case FULL -> {
          requireSequence(!inRecord);
          records.add(fragment);
        }
        case FIRST -> {
          requireSequence(!inRecord);
          pending.reset();
          pending.writeBytes(fragment);
          inRecord = true;
        }
        case MIDDLE -> {
          requireSequence(inRecord);
          pending.writeBytes(fragment);
        }
        case LAST -> {
          requireSequence(inRecord);
          pending.writeBytes(fragment);
          records.add(pending.toByteArray());
          inRecord = false;
        }
        default ->
            throw new CorruptionException(
                "unexpected " + logName + " fragment type", pos, -1, type.code());
      }
    }

    private void requireSequence(boolean ok) {
      if (!ok) {
        throw new CorruptionException(logName + " fragment out of sequence", pos, -1, -1);
      }
    }

    private void requireZero(int to) {
      for (int i = pos; i < to; i++) {
        if (data[i] != 0) {
          throw new CorruptionException(
              "non-zero " + logName + " block padding", i, 0, data[i] & 0xFF);
        }
      }
    }

    private boolean isZero(int from, int to) {
      for (int i = from; i < to; i++) {
        if (data[i] != 0) {
          return false;
        }
      }
      return true;
    }
  }
}
