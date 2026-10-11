package dev.shale;

import static org.assertj.core.api.Assertions.assertThat;

import dev.shale.env.FaultInjectionEnv;
import dev.shale.env.FaultInjectionEnv.DropMode;
import dev.shale.env.FaultInjectionEnv.EntryLoss;
import dev.shale.env.SimulatedCrash;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Power-loss durability (SH-04, M5 Step 4): crash the engine at <em>every</em> mutating file
 * operation of a {@code SYNC} workload, lose everything POSIX lets a power loss lose, reopen, and
 * require every acknowledged write. {@link ShaleCrashTest} covers a process crash; this covers the
 * machine losing power, where a synced file whose directory entry was never synced can vanish.
 *
 * <p>Each crash point is tried under both {@link DropMode}s and both {@link EntryLoss} models. A
 * write is acknowledged when its {@code put} returned; one interrupted by the crash may or may not
 * survive, and either is correct.
 */
@Tag("crash")
class ShalePowerLossTest {

  @TempDir private Path root;
  private int runs;

  @Test
  void syncPuts_surviveAPowerLoss_atEveryOperation() throws IOException {
    assertEveryCrashPointKeepsAcknowledgedWrites(new Workload(ShaleOptions.defaults(), 6, 8));
  }

  private void assertEveryCrashPointKeepsAcknowledgedWrites(Workload workload) throws IOException {
    long operations = countOperations(workload);
    assertThat(operations).isPositive();
    for (long crashAt = 0; crashAt < operations; crashAt++) {
      for (DropMode drop : DropMode.values()) {
        for (EntryLoss entries : EntryLoss.values()) {
          crashAndReopen(workload, new CrashPoint(crashAt, drop, entries));
        }
      }
    }
  }

  private long countOperations(Workload workload) throws IOException {
    Path runRoot = newRunRoot();
    FaultInjectionEnv env = new FaultInjectionEnv(runRoot);
    Map<String, String> acked = new LinkedHashMap<>();
    workload.run(env, runRoot.resolve("db"), acked);
    assertThat(acked).hasSize(workload.puts());
    return env.operationCount();
  }

  private void crashAndReopen(Workload workload, CrashPoint crash) throws IOException {
    Path runRoot = newRunRoot();
    Path dir = runRoot.resolve("db");
    FaultInjectionEnv env = new FaultInjectionEnv(runRoot);
    env.crashAt(crash.operation());
    Map<String, String> acked = new LinkedHashMap<>();
    try {
      workload.run(env, dir, acked);
    } catch (SimulatedCrash expected) {
      // the process is dead; what it acknowledged is in `acked`
    }
    String trace = String.join("\n", env.trace());
    env.dropUnsyncedData(crash.drop(), crash.entries(), crash.operation());

    try (Shale db = Shale.open(dir, workload.options(), Clock.system(), Metrics.NOOP)) {
      for (Map.Entry<String, String> write : acked.entrySet()) {
        assertThat(text(db.get(ascii(write.getKey()))))
            .as("acknowledged %s after %s; trace:%n%s", write.getKey(), crash, trace)
            .isEqualTo(write.getValue());
      }
    }
  }

  /** {@code puts} SYNC records of {@code valueBytes}-byte values, under {@code options}. */
  private record Workload(ShaleOptions options, int puts, int valueBytes) {

    /** Opens, writes, closes; records each write as its put returns, so a crash keeps the acks. */
    void run(FaultInjectionEnv env, Path dir, Map<String, String> acked) throws IOException {
      try (Shale db = Shale.open(dir, options.withEnv(env), Clock.system(), Metrics.NOOP)) {
        for (int i = 0; i < puts; i++) {
          String key = "key-" + i;
          String value = (i + "-").repeat(valueBytes).substring(0, valueBytes);
          db.put(ascii(key), ascii(value), Durability.SYNC);
          acked.put(key, value);
        }
      }
    }
  }

  /** Crash at {@code operation}, then lose what {@code drop} and {@code entries} say. */
  private record CrashPoint(long operation, DropMode drop, EntryLoss entries) {}

  private Path newRunRoot() throws IOException {
    Path runRoot = root.resolve("run-" + runs++);
    Files.createDirectories(runRoot);
    return runRoot;
  }

  private static byte[] ascii(String text) {
    return text.getBytes(StandardCharsets.US_ASCII);
  }

  private static String text(byte[] bytes) {
    return bytes == null ? null : new String(bytes, StandardCharsets.US_ASCII);
  }
}
