package dev.shale;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ClockTest {

  @Test
  void systemClock_nanoTimeIsMonotonic() {
    Clock clock = Clock.system();

    long first = clock.nanoTime();
    long second = clock.nanoTime();

    assertThat(second).isGreaterThanOrEqualTo(first);
    assertThat(clock.epochMillis()).isPositive();
  }

  @Test
  void manualClock_advancesOnlyWhenTold() {
    ManualClock clock = new ManualClock();
    assertThat(clock.nanoTime()).isZero();

    clock.advanceNanos(500).setMillis(1234);

    assertThat(clock.nanoTime()).isEqualTo(500);
    assertThat(clock.epochMillis()).isEqualTo(1234);
  }
}
