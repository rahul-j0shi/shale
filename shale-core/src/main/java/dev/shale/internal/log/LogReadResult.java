package dev.shale.internal.log;

import dev.shale.internal.annotations.Immutable;
import java.util.List;

/**
 * What {@link LogReader} found in one log file: the complete records, where the last one ended, and
 * how the file ended after it. Corruption is never a result: it is thrown.
 *
 * @param version the framing version from the file header
 * @param records the payload of every complete logical record, in append order
 * @param endOffset the file offset just past the last complete record (16 if there is none)
 * @param tail how the file ended after {@code endOffset}
 * @param tailOffset where the torn part begins — the torn header, the fragment whose payload is
 *     short, or the first unwritten byte — or the file's length when the tail is {@link Tail#CLEAN}
 *     or {@link Tail#OPEN_RECORD}
 */
@Immutable
public record LogReadResult(
    int version, List<byte[]> records, int endOffset, Tail tail, int tailOffset) {

  /**
   * How a log ends. Every value but {@link #CLEAN} is a torn tail: bytes a crash left unwritten, so
   * no record in them was ever acknowledged. Whether to accept one is the caller's policy.
   */
  public enum Tail {
    /** The last record is complete and nothing follows it but legal padding. */
    CLEAN,
    /** The file is shorter than its 16-byte file header: a crash while the log was created. */
    TORN_FILE_HEADER,
    /** Fewer bytes remain than a fragment header needs. */
    TORN_HEADER,
    /** Every byte from here to the end of the file is zero: allocated, never written. */
    UNWRITTEN,
    /** A fragment's payload runs past the end of the file. */
    TORN_PAYLOAD,
    /** The file ends inside a record begun with {@code FIRST}. */
    OPEN_RECORD
  }
}
