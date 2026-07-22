package dev.shale.model;

import dev.shale.Durability;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Runs a seeded random operation sequence against {@link ReferenceBackend} and the {@link
 * ReferenceModel} oracle, asserting agreement after every batch of operations and on full
 * iteration. The highest-value test in the project (testing.md §1).
 *
 * <p>M0 exercises put / overwrite / delete / get / scan. restart, flush, compaction, and snapshot
 * reads are added to this same harness as those mechanisms arrive (M1–M7).
 */
@Tag("model")
class StorageBackendModelTest {

  private static final int OPERATIONS = 5_000;
  private static final int KEY_SPACE = 32; // small, to force overwrites and deletes

  @Test
  void randomOperations_matchTreeMapModel() {
    long seed = Seeds.resolve();
    try {
      run(seed);
    } catch (AssertionError failure) {
      throw new AssertionError(
          "model divergence — reproduce with -Dshale.test.seed=" + seed, failure);
    }
  }

  @Test
  void pinnedRegression_seed1() {
    run(1L); // pin a representative seed so a known-good sequence always runs
  }

  private void run(long seed) {
    Random random = new Random(seed);
    ReferenceBackend backend = new ReferenceBackend();
    ReferenceModel model = new ReferenceModel();
    List<byte[]> probeKeys = allKeys();

    for (int op = 0; op < OPERATIONS; op++) {
      byte[] key = key(random.nextInt(KEY_SPACE));
      if (random.nextInt(100) < 70) { // put / overwrite
        byte[] value = value(random);
        backend.put(key, value, Durability.NONE);
        model.put(key, value);
      } else { // delete
        backend.delete(key, Durability.NONE);
        model.delete(key);
      }
      if (op % 100 == 0) {
        Backends.assertMatches(backend, model, probeKeys);
      }
    }
    Backends.assertMatches(backend, model, probeKeys); // final full check
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
