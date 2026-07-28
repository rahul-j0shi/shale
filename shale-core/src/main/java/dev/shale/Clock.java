package dev.shale;

/**
 * The engine's injected time source. Every duration measurement and any future timed wait reads
 * time through a {@code Clock} rather than calling {@link System#nanoTime()} directly, so tests are
 * deterministic (testing.md §2) and simulation testing is possible later (M9).
 *
 * <p><b>Threading:</b> implementations must be safe for concurrent reads.
 */
public interface Clock {

  /** A monotonic nanosecond counter for measuring elapsed time; not wall-clock. */
  long nanoTime();

  /** Milliseconds since the epoch, for log timestamps. */
  long epochMillis();

  /** The production clock backed by the system time source. */
  static Clock system() {
    return SystemClock.INSTANCE;
  }
}
