package dev.shale;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** A {@link Metrics} sink that remembers what was emitted, for assertions in tests. */
final class RecordingMetrics implements Metrics {

  private final Map<String, Long> counters = new ConcurrentHashMap<>();
  private final Map<String, Long> gauges = new ConcurrentHashMap<>();
  private final Map<String, List<Long>> observations = new ConcurrentHashMap<>();

  @Override
  public void increment(String counter, long delta) {
    counters.merge(counter, delta, Long::sum);
  }

  @Override
  public void gauge(String gauge, long value) {
    gauges.put(gauge, value);
  }

  @Override
  public void record(String histogram, long value) {
    observations.computeIfAbsent(histogram, key -> new ArrayList<>()).add(value);
  }

  long counter(String name) {
    return counters.getOrDefault(name, 0L);
  }

  long gaugeValue(String name) {
    return gauges.getOrDefault(name, 0L);
  }

  int observationCount(String name) {
    return observations.getOrDefault(name, List.of()).size();
  }
}
