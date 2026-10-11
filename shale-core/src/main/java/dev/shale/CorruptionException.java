package dev.shale;

import java.nio.file.Path;
import java.util.Optional;

/**
 * The bytes read from disk (or a decoded structure) are wrong: checksum mismatch, bad magic, or an
 * impossible value. Callers must stop and escalate — never retry, never repair (N4). Always carries
 * location and evidence (errors-and-logging.md §1): the file, the offset, and the expected and
 * actual values.
 *
 * <p>A decoder deep in a format does not know which file it is decoding, so it throws without one,
 * with the offset relative to what it was given ({@code -1} means "not applicable"). The reader
 * that opened the file adds it with {@link #inFile}, keeping the decoder's exception as the cause.
 */
public final class CorruptionException extends ShaleException {

  private static final long serialVersionUID = 1L;

  private final String detail;
  private final String file; // a String, not a Path: an exception is Serializable and Path is not
  private final long offsetBytes;
  private final long expectedValue;
  private final long actualValue;

  public CorruptionException(String message) {
    this(message, -1, -1, -1);
  }

  public CorruptionException(
      String message, long offsetBytes, long expectedValue, long actualValue) {
    this(null, message, offsetBytes, expectedValue, actualValue, null);
  }

  /** Corruption found in {@code file}; the message names it. */
  public CorruptionException(
      Path file, String message, long offsetBytes, long expectedValue, long actualValue) {
    this(file.toString(), message, offsetBytes, expectedValue, actualValue, null);
  }

  private CorruptionException(
      String file,
      String detail,
      long offsetBytes,
      long expectedValue,
      long actualValue,
      Throwable cause) {
    super(
        (file == null ? "" : file + ": ")
            + detail
            + " (offset="
            + offsetBytes
            + " expected="
            + expectedValue
            + " actual="
            + actualValue
            + ")",
        cause);
    this.detail = detail;
    this.file = file;
    this.offsetBytes = offsetBytes;
    this.expectedValue = expectedValue;
    this.actualValue = actualValue;
  }

  /**
   * This corruption, located in {@code file}: the same message, offset and values, with this
   * exception as the cause. A reader calls it on what a decoder threw.
   */
  public CorruptionException inFile(Path file) {
    return new CorruptionException(
        file.toString(), detail, offsetBytes, expectedValue, actualValue, this);
  }

  /** The file the corruption is in, if the thrower knew it. */
  public Optional<Path> file() {
    return Optional.ofNullable(file).map(Path::of);
  }

  public long offsetBytes() {
    return offsetBytes;
  }

  public long expectedValue() {
    return expectedValue;
  }

  public long actualValue() {
    return actualValue;
  }
}
