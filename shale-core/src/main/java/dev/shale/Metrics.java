package dev.shale;

/**
 * The engine's metrics sink. {@code shale-core} defines its own minimal interface with a no-op
 * default so the engine stays dependency-free and the application binds it to whatever it likes
 * (errors-and-logging.md §3). Names are lowercase dotted (e.g. {@code wal.sync.duration}); the unit
 * is the last segment when not obvious.
 *
 * <p><b>Threading:</b> implementations must accept concurrent calls from engine threads.
 */
public interface Metrics {

  /** A sink that discards everything; the default when the application binds none. */
  Metrics NOOP =
      new Metrics() {
        @Override
        public void increment(String counter, long delta) {}

        @Override
        public void gauge(String gauge, long value) {}

        @Override
        public void record(String histogram, long value) {}
      };

  /** Adds {@code delta} to a monotonic counter. */
  void increment(String counter, long delta);

  /** Sets a point-in-time gauge value. */
  void gauge(String gauge, long value);

  /** Records one observation into a distribution (e.g. a latency in nanos). */
  void record(String histogram, long value);
}
