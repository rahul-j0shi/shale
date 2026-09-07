package dev.shale;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The engine's own {@code @ThreadSafe} contract, which until now had no test: {@code Shale} claims
 * that writers serialise on its write lock while readers proceed from a single volatile read of the
 * {@code ReadView}, with no lock. Everything downstream of that claim — the memtable switch, the
 * flush that publishes a new view mid-read, and the cursor's pinning of the tables it can reach —
 * is exercised here by running writers and readers against one engine while flushes happen
 * underneath them.
 *
 * <p><b>What is asserted is what the engine promises today.</b> A reader may see a key or not see
 * it — no ordering between an unsynchronised reader and a concurrent writer is claimed — but it may
 * never see a <em>wrong</em> one: every value read back must be the one derivable from its key, and
 * every scan must be ordered. Snapshot isolation is deliberately not asserted: neither {@link
 * Cursor} nor {@link StorageBackend} promises it before M7, and encoding it here would pin a
 * semantics that has not been chosen.
 *
 * <p><b>What cannot be asserted yet.</b> N6's "the cursor returns every reference it took" has no
 * direct observation: {@code SSTableReader}'s count is private and adding a getter for one
 * assertion is the trade M4 already refused. Over-release is caught indirectly — it closes a
 * channel early, and the reads that follow would throw — while a leak is invisible until M5's
 * delete-on-zero makes an un-deleted file the symptom.
 *
 * <p>Coordination is by latch and completion flag, never {@code Thread.sleep} (N8), and every
 * failure message carries the seed.
 */
class ShaleConcurrencyTest {

  private static final int WRITERS = 4;
  private static final int READERS = 4;
  private static final int KEYS_PER_WRITER = 250;
  private static final int SEEDS = 5;
  private static final int GENERATIONS = 2;

  /** Small enough that the run switches and flushes repeatedly; large enough to stay quick. */
  private static final long SMALL_BUFFER_BYTES = 4096;

  @TempDir private Path root;

  @Test
  void concurrentWritersAndReaders_neverObserveAWrongValue() throws Exception {
    for (int seed = 0; seed < SEEDS; seed++) {
      runOneSeed(root.resolve("seed" + seed), seed);
    }
  }

  private void runOneSeed(Path directory, long seed) throws Exception {
    RecordingMetrics metrics = new RecordingMetrics();
    ExecutorService pool = Executors.newFixedThreadPool(WRITERS + READERS);
    try (Shale db = Shale.open(directory, Clock.system(), metrics, SMALL_BUFFER_BYTES)) {
      CountDownLatch ready = new CountDownLatch(WRITERS + READERS);
      StartGate gate = new StartGate(ready, new CountDownLatch(1));
      AtomicBoolean writersDone = new AtomicBoolean();
      List<Future<?>> tasks = new ArrayList<>();

      for (int w = 0; w < WRITERS; w++) {
        int writer = w;
        tasks.add(pool.submit(() -> writeKeys(db, writer, gate)));
      }
      for (int r = 0; r < READERS; r++) {
        long readerSeed = seed * 31 + r;
        tasks.add(pool.submit(() -> readUntilDone(db, readerSeed, gate, writersDone)));
      }

      ready.await();
      gate.go().countDown();
      for (int i = 0; i < WRITERS; i++) {
        tasks.get(i).get(); // writers first: their completion releases the readers
      }
      writersDone.set(true);
      for (Future<?> task : tasks) {
        task.get(); // surfaces any assertion failure from a worker thread
      }

      // The run is only meaningful if the view was actually republished underneath the readers.
      assertThat(metrics.counter("memtable.switch.count"))
          .as("seed %d: the buffer must be small enough to force switches", seed)
          .isGreaterThan(0);
      assertThat(metrics.counter("flush.count"))
          .as("seed %d: switches must have flushed, republishing the view mid-read", seed)
          .isGreaterThan(0);

      // Every acknowledged write is readable, with its own value, once the writers have finished.
      for (int w = 0; w < WRITERS; w++) {
        for (int index = 0; index < KEYS_PER_WRITER; index++) {
          String key = key(w, index);
          // Newest wins: the last version written must be the one that survives, whichever
          // memtable or SSTable each version came to rest in.
          assertThat(db.get(bytes(key)))
              .as("seed %d: newest version of %s after all writers finished", seed, key)
              .isEqualTo(bytes(value(key, GENERATIONS)));
        }
      }
    } finally {
      pool.shutdownNow();
    }
  }

  /**
   * Writes every key twice. The second generation is what makes this a test of reconciliation
   * rather than of insertion: the two versions of a key routinely land in different sources — one
   * flushed to an SSTable, one still in a memtable — so returning the older one is a live failure
   * mode, not a hypothetical.
   */
  private static void writeKeys(Shale db, int writer, StartGate gate) {
    gate.arriveAndWait();
    for (int generation = 1; generation <= GENERATIONS; generation++) {
      for (int index = 0; index < KEYS_PER_WRITER; index++) {
        String key = key(writer, index);
        db.put(bytes(key), bytes(value(key, generation)), Durability.NONE);
      }
    }
  }

  /**
   * Probes random keys and ranges until the writers finish. A miss is legal — an unsynchronised
   * reader has no claim on when a concurrent write becomes visible — but a hit must be correct.
   */
  private static void readUntilDone(
      Shale db, long seed, StartGate gate, AtomicBoolean writersDone) {
    Random random = new Random(seed);
    gate.arriveAndWait();
    do {
      String key = key(random.nextInt(WRITERS), random.nextInt(KEYS_PER_WRITER));
      byte[] found = db.get(bytes(key));
      if (found != null) {
        assertThat(text(found))
            .as("seed %d: value read for %s must be one of its own versions", seed, key)
            .isIn(value(key, 1), value(key, 2));
      }
      scanAndCheck(db, seed, random);
    } while (!writersDone.get());
  }

  /** A bounded scan must be ordered, in range, and free of mismatched values. */
  private static void scanAndCheck(Shale db, long seed, Random random) {
    int writer = random.nextInt(WRITERS);
    int from = random.nextInt(KEYS_PER_WRITER);
    int to = Math.min(from + 32, KEYS_PER_WRITER);
    byte[] lower = bytes(key(writer, from));
    byte[] upper = bytes(key(writer, to));

    try (Cursor cursor = db.scan(lower, upper)) {
      String previous = null;
      while (cursor.isValid()) {
        String key = text(cursor.key());
        assertThat(text(cursor.value()))
            .as("seed %d: scanned value for %s", seed, key)
            .isIn(value(key, 1), value(key, 2));
        assertThat(key)
            .as("seed %d: scan stays within its bounds", seed)
            .isBetween(text(lower), text(upper));
        if (previous != null) {
          assertThat(key).as("seed %d: scan is ordered", seed).isGreaterThan(previous);
        }
        previous = key;
        cursor.next();
      }
    }
  }

  /**
   * Releases every worker at once. Each arrives, reports ready, and blocks until the last one has
   * arrived — so the threads genuinely overlap instead of the first finishing before the last
   * starts. Latches, not sleeps (N8).
   */
  private record StartGate(CountDownLatch ready, CountDownLatch go) {

    void arriveAndWait() {
      ready.countDown();
      try {
        go.await();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("interrupted before starting", e);
      }
    }
  }

  private static String key(int writer, int index) {
    return String.format("w%d-k%05d", writer, index);
  }

  private static String value(String key, int generation) {
    return "v" + generation + "-" + key;
  }

  private static String text(byte[] value) {
    return new String(value, StandardCharsets.US_ASCII);
  }

  private static byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.US_ASCII);
  }
}
