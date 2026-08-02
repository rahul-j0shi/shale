package dev.shale.memtable;

import static org.assertj.core.api.Assertions.assertThat;

import dev.shale.ByteRange;
import dev.shale.BytewiseComparator;
import dev.shale.internal.key.InternalKey;
import dev.shale.internal.key.InternalKeyComparator;
import dev.shale.internal.key.ValueType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/**
 * The single-writer / lock-free-reader contract (ADR-0009): while one thread inserts, any number of
 * readers may traverse concurrently and must never throw, never observe an out-of-order entry, and
 * never observe a torn node (a node whose value does not match its key). Coordination uses latches
 * and a completion flag — no {@code Thread.sleep} (N8).
 */
class SkiplistMemtableConcurrencyTest {

  private static final int KEYS = 4000;
  private static final int READERS = 4;

  @Test
  void concurrentReaders_seeAConsistentGrowingList_whileOneWriterInserts() throws Exception {
    SkiplistMemtable memtable = new SkiplistMemtable(BytewiseComparator.INSTANCE, 7L);
    InternalKeyComparator ordering = new InternalKeyComparator(BytewiseComparator.INSTANCE);

    // Insert in shuffled key order so the ordering logic (not just append) is exercised.
    List<Integer> insertionOrder = new ArrayList<>();
    for (int id = 0; id < KEYS; id++) {
      insertionOrder.add(id);
    }
    Collections.shuffle(insertionOrder, new java.util.Random(7L));

    CountDownLatch ready = new CountDownLatch(READERS + 1);
    CountDownLatch go = new CountDownLatch(1);
    CompletionFlag done = new CompletionFlag();

    ExecutorService pool = Executors.newFixedThreadPool(READERS + 1);
    try {
      List<Future<?>> readerFutures = new ArrayList<>();
      for (int r = 0; r < READERS; r++) {
        readerFutures.add(
            pool.submit(
                () -> {
                  ready.countDown();
                  await(go);
                  do {
                    verifySnapshot(memtable, ordering);
                  } while (!done.value);
                  verifySnapshot(memtable, ordering); // one more after the writer finished
                  return null;
                }));
      }
      Future<?> writerFuture =
          pool.submit(
              () -> {
                ready.countDown();
                await(go);
                long sequence = 1;
                for (int id : insertionOrder) {
                  memtable.add(internalKey(id, sequence), expectedValue(id));
                  sequence++;
                }
                done.value = true;
                return null;
              });

      await(ready); // everyone parked on `go`
      go.countDown(); // release them together

      writerFuture.get();
      for (Future<?> future : readerFutures) {
        future.get(); // rethrows any assertion or exception a reader hit
      }
    } finally {
      pool.shutdownNow();
    }

    // Final state: every key present exactly once, in ascending order, values intact.
    List<Memtable.Entry> entries = memtable.entries();
    assertThat(entries).hasSize(KEYS);
    verifyOrderedAndIntact(entries, ordering);
    List<Integer> ids = entries.stream().map(e -> idOf(e.internalKey())).sorted().toList();
    assertThat(ids).isEqualTo(insertionOrder.stream().sorted().toList());
  }

  private static void verifySnapshot(SkiplistMemtable memtable, InternalKeyComparator ordering) {
    verifyOrderedAndIntact(memtable.entries(), ordering);
  }

  /** Every entry is strictly after the previous one and carries the value its key implies. */
  private static void verifyOrderedAndIntact(
      List<Memtable.Entry> entries, InternalKeyComparator ordering) {
    byte[] previous = null;
    for (Memtable.Entry entry : entries) {
      if (previous != null) {
        assertThat(ordering.compare(ByteRange.of(previous), ByteRange.of(entry.internalKey())))
            .as("entries must be strictly ascending")
            .isNegative();
      }
      assertThat(entry.value())
          .as("value must match the key (no torn node)")
          .containsExactly(expectedValue(idOf(entry.internalKey())));
      previous = entry.internalKey();
    }
  }

  private static byte[] internalKey(int id, long sequence) {
    return new InternalKey(userKey(id), sequence, ValueType.PUT).encode();
  }

  private static int idOf(byte[] internalKey) {
    return java.nio.ByteBuffer.wrap(internalKey, 0, Integer.BYTES).getInt(); // big-endian id
  }

  private static byte[] userKey(int id) {
    return java.nio.ByteBuffer.allocate(Integer.BYTES).putInt(id).array();
  }

  /** A value fully determined by the key, so a torn node (mismatched key/value) is detectable. */
  private static byte[] expectedValue(int id) {
    byte[] key = userKey(id);
    return new byte[] {(byte) Arrays.hashCode(key), (byte) key.length, (byte) ~id};
  }

  private static void await(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while coordinating the stress test", e);
    }
  }

  /** A tiny mutable box with a volatile flag; readers spin on it instead of sleeping. */
  private static final class CompletionFlag {
    private volatile boolean value;
  }
}
