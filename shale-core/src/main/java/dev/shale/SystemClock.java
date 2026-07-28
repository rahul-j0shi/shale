package dev.shale;

import dev.shale.internal.annotations.ThreadSafe;

/**
 * The one place in the engine that reads the real system clock (ADR-0008). Everything else takes a
 * {@link Clock}. The banned-API checks are suppressed here deliberately and nowhere else.
 */
@ThreadSafe
final class SystemClock implements Clock {

  static final SystemClock INSTANCE = new SystemClock();

  private SystemClock() {}

  @Override
  public long nanoTime() {
    // CHECKSTYLE.OFF: RegexpSinglelineJava — the single audited wall-clock call site (ADR-0008)
    return System.nanoTime();
    // CHECKSTYLE.ON: RegexpSinglelineJava
  }

  @Override
  public long epochMillis() {
    // CHECKSTYLE.OFF: RegexpSinglelineJava — the single audited wall-clock call site (ADR-0008)
    return System.currentTimeMillis();
    // CHECKSTYLE.ON: RegexpSinglelineJava
  }
}
