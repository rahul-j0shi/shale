package dev.shale;

import dev.shale.internal.annotations.NotThreadSafe;

/** A clock the test advances explicitly; no wall-clock, no sleeping (testing.md §2). */
@NotThreadSafe
final class ManualClock implements Clock {

  private long nanos;
  private long millis;

  ManualClock advanceNanos(long delta) {
    nanos += delta;
    return this;
  }

  ManualClock setMillis(long value) {
    millis = value;
    return this;
  }

  @Override
  public long nanoTime() {
    return nanos;
  }

  @Override
  public long epochMillis() {
    return millis;
  }
}
