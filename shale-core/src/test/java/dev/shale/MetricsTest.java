package dev.shale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.Test;

class MetricsTest {

  @Test
  void noop_acceptsEveryCall() {
    assertThatCode(
            () -> {
              Metrics.NOOP.increment("wal.append.count", 1);
              Metrics.NOOP.gauge("memtable.size.bytes", 42);
              Metrics.NOOP.record("wal.sync.duration", 1000);
            })
        .doesNotThrowAnyException();
  }

  @Test
  void recording_accumulatesCountersAndObservations() {
    RecordingMetrics metrics = new RecordingMetrics();

    metrics.increment("wal.append.count", 1);
    metrics.increment("wal.append.count", 2);
    metrics.gauge("memtable.size.bytes", 128);
    metrics.record("wal.sync.duration", 500);

    assertThat(metrics.counter("wal.append.count")).isEqualTo(3);
    assertThat(metrics.gaugeValue("memtable.size.bytes")).isEqualTo(128);
    assertThat(metrics.observationCount("wal.sync.duration")).isEqualTo(1);
  }
}
