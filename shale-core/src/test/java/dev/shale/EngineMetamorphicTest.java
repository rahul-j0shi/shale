package dev.shale;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Metamorphic testing, after Pebble's (M5 Step 8; SH-05): one seeded history of puts, overwrites
 * and deletes runs under configurations that must not change any answer — a 96-byte write buffer (a
 * flush every few writes, hundreds of tables) or a 4 MiB one (none); the skiplist memtable or the
 * tree one; no restarts, or a clean reopen every 40 operations. Every configuration must give the
 * {@code TreeMap} oracle's {@code get} and {@code scan} results at every check, and so the same
 * results as each other.
 *
 * <p>The seed is {@code -Dshale.test.seed}, or a fresh one named in any failure.
 */
@Tag("model")
class EngineMetamorphicTest {

  private static final int OPERATIONS = 600;
  private static final int KEY_SPACE = 24;
  private static final int CHECK_EVERY = 50;

  @TempDir private Path root;

  @Test
  void oneHistory_givesTheSameAnswers_underEveryConfiguration() throws IOException {
    long seed = seed();
    List<Op> history = history(seed);
    List<String> reference = null;
    for (long buffer : new long[] {96, 4L * 1024 * 1024}) {
      for (MemtableKind memtable : MemtableKind.values()) {
        for (int reopenEvery : new int[] {0, 40}) {
          Config config = new Config(buffer, memtable, reopenEvery);
          List<String> observed;
          try {
            observed = run(history, config);
          } catch (AssertionError | IOException | ShaleException e) {
            throw new AssertionError(
                config + " diverged; reproduce with -Dshale.test.seed=" + seed, e);
          }
          if (reference == null) {
            reference = observed;
          }
          assertThat(observed)
              .as("%s against the first configuration; seed %d", config, seed)
              .isEqualTo(reference);
        }
      }
    }
  }

  /** Runs {@code history} under {@code config}; returns every check's scan, as text. */
  private List<String> run(List<Op> history, Config config) throws IOException {
    Path dir = root.resolve(config.toString());
    Files.createDirectories(dir);
    TreeMap<String, String> oracle = new TreeMap<>();
    List<String> observed = new ArrayList<>();
    Shale db = open(dir, config);
    try {
      for (int i = 0; i < history.size(); i++) {
        Op op = history.get(i);
        if (op.value() == null) {
          db.delete(op.key(), Durability.NONE);
          oracle.remove(hex(op.key()));
        } else {
          db.put(op.key(), op.value(), Durability.NONE);
          oracle.put(hex(op.key()), hex(op.value()));
        }
        if (config.reopenEvery() > 0 && i % config.reopenEvery() == config.reopenEvery() - 1) {
          db.close();
          db = open(dir, config);
        }
        if (i % CHECK_EVERY == CHECK_EVERY - 1 || i == history.size() - 1) {
          observed.add(check(db, oracle, i));
        }
      }
    } finally {
      db.close();
    }
    return observed;
  }

  /** Compares every key's {@code get}, and the full {@code scan}, with the oracle. */
  private static String check(Shale db, TreeMap<String, String> oracle, int after) {
    Map<String, String> scanned = new TreeMap<>();
    try (Cursor cursor = db.scan(null, null)) {
      for (; cursor.isValid(); cursor.next()) {
        scanned.put(hex(cursor.key()), hex(cursor.value()));
      }
    }
    assertThat(scanned).as("scan after operation %d", after).isEqualTo(oracle);
    for (int index = 0; index < KEY_SPACE; index++) {
      byte[] value = db.get(key(index));
      assertThat(value == null ? null : hex(value))
          .as("get key %d after operation %d", index, after)
          .isEqualTo(oracle.get(hex(key(index))));
    }
    return scanned.toString();
  }

  private static Shale open(Path dir, Config config) throws IOException {
    ShaleOptions options = ShaleOptions.defaults().withWriteBufferSizeBytes(config.buffer());
    return Shale.open(
        new Recovery.Request(dir, options, Clock.system(), Metrics.NOOP, config.memtable()));
  }

  /** Puts (60%, so overwrites are common over 24 keys) and deletes, from {@code seed}. */
  private static List<Op> history(long seed) {
    Random random = new Random(seed);
    List<Op> ops = new ArrayList<>(OPERATIONS);
    for (int i = 0; i < OPERATIONS; i++) {
      byte[] key = key(random.nextInt(KEY_SPACE));
      if (random.nextInt(100) < 60) {
        byte[] value = new byte[1 + random.nextInt(12)];
        random.nextBytes(value);
        ops.add(new Op(key, value));
      } else {
        ops.add(new Op(key, null));
      }
    }
    return ops;
  }

  private static long seed() {
    String property = System.getProperty("shale.test.seed");
    return property != null ? Long.parseLong(property) : new SecureRandom().nextLong();
  }

  private static byte[] key(int i) {
    return new byte[] {'k', (byte) (i >>> 8), (byte) i};
  }

  private static String hex(byte[] bytes) {
    return HexFormat.of().formatHex(bytes);
  }

  /** A put of {@code value}, or a delete when it is null. */
  private record Op(byte[] key, byte[] value) {

    @Override
    public String toString() {
      return hex(key) + (value == null ? " delete" : " = " + Arrays.toString(value));
    }
  }

  /** One configuration that must not change any answer. */
  private record Config(long buffer, MemtableKind memtable, int reopenEvery) {

    @Override
    public String toString() {
      return "buffer-" + buffer + "-" + memtable + "-reopen-" + reopenEvery;
    }
  }
}
