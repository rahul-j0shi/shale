package dev.shale;

/**
 * The bytes read from disk (or a decoded structure) are wrong: checksum mismatch, bad magic, or an
 * impossible value. Callers must stop and escalate — never retry, never repair (N4). Always carries
 * location and evidence (errors-and-logging.md §1).
 *
 * <p>File-aware constructors are added when file readers exist (M3); at M0 the offset is relative
 * to the decoded structure, and {@code -1} means "not applicable".
 */
public final class CorruptionException extends ShaleException {

  private static final long serialVersionUID = 1L;

  private final long offsetBytes;
  private final long expectedValue;
  private final long actualValue;

  public CorruptionException(String message) {
    this(message, -1, -1, -1);
  }

  public CorruptionException(
      String message, long offsetBytes, long expectedValue, long actualValue) {
    super(
        message
            + " (offset="
            + offsetBytes
            + " expected="
            + expectedValue
            + " actual="
            + actualValue
            + ")");
    this.offsetBytes = offsetBytes;
    this.expectedValue = expectedValue;
    this.actualValue = actualValue;
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
