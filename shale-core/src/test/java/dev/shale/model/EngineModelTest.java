package dev.shale.model;

import dev.shale.Clock;
import dev.shale.Durability;
import dev.shale.Metrics;
import dev.shale.Shale;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Drives the real {@link Shale} engine through a seeded random operation sequence and diffs it
 * against the {@link ReferenceModel} oracle, with a small write buffer so memtable switches happen
 * constantly and periodic restarts so recovery is exercised too. This is the M2 correctness surface
 * — lock-free reads, the active → immutable handoff, and WAL replay — checked end to end
 * (testing.md §1).
 */
@Tag("model")
class EngineModelTest {

  private static final int OPERATIONS = 5_000;
  private static final int KEY_SPACE = 32; // small, to force overwrites and deletes
  private static final long TINY_BUFFER_BYTES = 256; // small, to force frequent switches
  private static final int RESTART_EVERY = 700;

  @TempDir private Path dir;

  @Test
  void randomOperationsWithSwitchesAndRestarts_matchTreeMapModel() throws IOException {
    long seed = Seeds.resolve();
    try {
      run(seed);
    } catch (AssertionError failure) {
      throw new AssertionError(
          "model divergence — reproduce with -Dshale.test.seed=" + seed, failure);
    }
  }

  @Test
  void pinnedRegression_seed1() throws IOException {
    run(1L);
  }

  private void run(long seed) throws IOException {
    Random random = new Random(seed);
    ReferenceModel model = new ReferenceModel();
    List<byte[]> probeKeys = allKeys();

    Shale engine = open();
    try {
      for (int op = 0; op < OPERATIONS; op++) {
        byte[] key = key(random.nextInt(KEY_SPACE));
        if (random.nextInt(100) < 70) { // put / overwrite
          byte[] value = value(random);
          engine.put(key, value, Durability.NONE);
          model.put(key, value);
        } else { // delete
          engine.delete(key, Durability.NONE);
          model.delete(key);
        }
        if (op % 100 == 0) {
          Backends.assertMatches(engine, model, probeKeys);
        }
        if (op % RESTART_EVERY == RESTART_EVERY - 1) {
          engine.close(); // clean restart: reopen must recover the full logical state
          engine = open();
          Backends.assertMatches(engine, model, probeKeys);
        }
      }
      Backends.assertMatches(engine, model, probeKeys); // final full check
    } finally {
      engine.close();
    }
  }

  private Shale open() throws IOException {
    return Shale.open(dir, Clock.system(), Metrics.NOOP, TINY_BUFFER_BYTES);
  }

  private static List<byte[]> allKeys() {
    List<byte[]> keys = new ArrayList<>(KEY_SPACE);
    for (int i = 0; i < KEY_SPACE; i++) {
      keys.add(key(i));
    }
    return keys;
  }

  private static byte[] key(int i) {
    return new byte[] {(byte) (i >>> 8), (byte) i};
  }

  private static byte[] value(Random random) {
    byte[] value = new byte[1 + random.nextInt(8)];
    random.nextBytes(value);
    return value;
  }
}
