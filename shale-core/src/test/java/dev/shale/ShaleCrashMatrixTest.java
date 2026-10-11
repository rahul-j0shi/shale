package dev.shale;

import static org.assertj.core.api.Assertions.assertThat;

import dev.shale.env.Env;
import dev.shale.env.FaultInjectionEnv;
import dev.shale.env.FaultInjectionEnv.DropMode;
import dev.shale.env.FaultInjectionEnv.EntryLoss;
import dev.shale.env.SimulatedCrash;
import dev.shale.internal.key.InternalKeyComparator;
import dev.shale.iterator.InternalIterator;
import dev.shale.manifest.FileMetadata;
import dev.shale.manifest.VersionSet;
import dev.shale.sstable.SSTableReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The crash matrix (M5 Step 8; SH-04, SH-05): each scripted workload is crashed at <em>every</em>
 * mutating file operation, then loses what a power loss may take — unsynced bytes ({@link
 * DropMode}) and unsynced directory entries ({@link EntryLoss}) — and is reopened on the real file
 * system. After every one of those reopens:
 *
 * <ul>
 *   <li>the reopen succeeds: a crash followed by a power loss never reads as corruption;
 *   <li>every write acknowledged under {@code SYNC} is present, with its value;
 *   <li>nothing appears that was never written;
 *   <li>no internal key is in two live tables (the manifest's log number keeps a flushed segment
 *       from being replayed again);
 *   <li>{@code get} and {@code scan} agree on every key.
 * </ul>
 *
 * <p>{@code TORN} mode keeps a seeded prefix of unsynced bytes; the seed is {@code
 * -Dshale.test.seed}, or a fixed one, and every failure message names it.
 */
@Tag("crash")
class ShaleCrashMatrixTest {

  private static final InternalKeyComparator ORDERING =
      new InternalKeyComparator(BytewiseComparator.INSTANCE);

  private static final long SEED = Long.getLong("shale.test.seed", 0x5EED_CAFEL);

  @TempDir private Path root;
  private int runs;

  @Test
  void syncPuts() throws IOException {
    runMatrix(new Workload("sync puts", ShaleOptions.defaults(), puts(6, 20)));
  }

  @Test
  void putsForcingTwoFlushes() throws IOException {
    ShaleOptions options = ShaleOptions.defaults().withWriteBufferSizeBytes(200);
    runMatrix(new Workload("two flushes", options, puts(7, 60)));
  }

  @Test
  void aRecordFragmentedAcrossThreeBlocks_justBeforeASwitch() throws IOException {
    // 70,000 bytes span three 32 KiB blocks (FIRST, MIDDLE, LAST), and fill the 64 KiB buffer.
    ShaleOptions options = ShaleOptions.defaults().withWriteBufferSizeBytes(64 * 1024);
    Script script =
        (db, ledger) -> {
          ledger.put(db, "small-1", "x".repeat(10));
          ledger.put(db, "big", "b".repeat(70_000));
          ledger.put(db, "small-2", "y".repeat(10));
        };
    runMatrix(new Workload("fragmented record then switch", options, script));
  }

  @Test
  void aFailedFlush_thenACrash_thenRecovery() throws IOException {
    ShaleOptions options = ShaleOptions.defaults().withWriteBufferSizeBytes(200);
    Workload workload = new Workload("failed flush", options, puts(7, 60));
    runMatrix(workload.failingFirst("create", ".sst.tmp"));
  }

  @Test
  void aFlushThatRollsTheManifestOver() throws IOException {
    ShaleOptions options =
        ShaleOptions.defaults().withWriteBufferSizeBytes(200).withManifestRolloverBytes(200);
    runMatrix(new Workload("manifest rollover", options, puts(7, 60)));
  }

  @Test
  void aCrashDuringRecovery_thenReopenAgain() throws IOException {
    // The WAL holds acknowledged writes no table has; the crashed run is the open that recovers
    // them: its recovery flush, new manifest, CURRENT switch and cleanup.
    Script prepare = puts(5, 30);
    Script reopenOnly = (db, ledger) -> {};
    runMatrix(new Workload("recovery", ShaleOptions.defaults(), reopenOnly).preparedBy(prepare));
  }

  // ------------------------------------------------------------------ the matrix

  private void runMatrix(Workload workload) throws IOException {
    long failAt = workload.failFirst() == null ? -1 : findFirst(workload);
    long operations = dryRun(workload, failAt).operationCount();
    assertThat(operations).isPositive();
    for (long crashAt = 0; crashAt < operations; crashAt++) {
      for (DropMode drop : DropMode.values()) {
        for (EntryLoss entries : EntryLoss.values()) {
          crashAndCheck(workload, failAt, new CrashPoint(crashAt, drop, entries));
        }
      }
    }
  }

  /** The index of the operation the workload's injected failure hits, from a fault-free run. */
  private long findFirst(Workload workload) throws IOException {
    for (String line : dryRun(workload, -1).trace()) {
      String[] parts = line.split(" ");
      if (parts[1].equals(workload.failFirst()[0]) && parts[2].endsWith(workload.failFirst()[1])) {
        return Long.parseLong(parts[0]);
      }
    }
    throw new AssertionError(workload.name() + ": no operation to fail");
  }

  private FaultInjectionEnv dryRun(Workload workload, long failAt) throws IOException {
    Run run = newRun(workload);
    if (failAt >= 0) {
      run.env().failOn(failAt, new IOException("injected"));
    }
    run.execute(workload);
    return run.env();
  }

  private void crashAndCheck(Workload workload, long failAt, CrashPoint crash) throws IOException {
    Run run = newRun(workload);
    if (failAt >= 0) {
      run.env().failOn(failAt, new IOException("injected"));
    }
    run.env().crashAt(crash.operation());
    try {
      run.execute(workload);
    } catch (SimulatedCrash expected) {
      // the process died: what it acknowledged is in the ledger
    }
    String context =
        workload.name()
            + ", "
            + crash
            + ", seed "
            + SEED
            + "; trace:\n"
            + String.join("\n", run.env().trace());
    run.env().dropUnsyncedData(crash.drop(), crash.entries(), SEED ^ crash.operation());

    try (Shale db = Shale.open(run.dir(), workload.options(), Clock.system(), Metrics.NOOP)) {
      Map<String, String> scanned = scanAll(db);
      for (Map.Entry<String, String> ack : run.ledger().acknowledged().entrySet()) {
        assertThat(text(db.get(ascii(ack.getKey()))))
            .as("acknowledged %s; %s", ack.getKey(), context)
            .isEqualTo(ack.getValue());
      }
      for (Map.Entry<String, String> found : scanned.entrySet()) {
        assertThat(run.ledger().attempted().getOrDefault(found.getKey(), Set.of()))
            .as("%s=%s was never written; %s", found.getKey(), found.getValue(), context)
            .contains(found.getValue());
        assertThat(text(db.get(ascii(found.getKey()))))
            .as("get agrees with scan on %s; %s", found.getKey(), context)
            .isEqualTo(found.getValue());
      }
    }
    assertNoInternalKeyInTwoTables(run.dir(), context);
  }

  private static void assertNoInternalKeyInTwoTables(Path dir, String context) throws IOException {
    Map<String, Long> owner = new HashMap<>();
    for (FileMetadata file : VersionSet.readState(Env.DEFAULT, dir).orElseThrow().files()) {
      Path table = dir.resolve(String.format("%06d.sst", file.number()));
      try (SSTableReader reader = SSTableReader.open(table, ORDERING);
          InternalIterator entries = reader.iterator()) {
        for (entries.seekToFirst(); entries.valid(); entries.next()) {
          String key = HexFormat.of().formatHex(entries.internalKey());
          Long previous = owner.put(key, file.number());
          assertThat(previous)
              .as("internal key %s in tables %s and %d; %s", key, previous, file.number(), context)
              .isNull();
        }
      }
    }
  }

  private Run newRun(Workload workload) throws IOException {
    Path runRoot = root.resolve("run-" + runs++);
    Files.createDirectories(runRoot);
    Run run = new Run(runRoot.resolve("db"), new FaultInjectionEnv(runRoot), new Ledger());
    if (workload.prepare() != null) {
      try (Shale db = Shale.open(run.dir(), workload.options(), Clock.system(), Metrics.NOOP)) {
        workload.prepare().run(db, run.ledger());
      }
    }
    return run;
  }

  private static Map<String, String> scanAll(Shale db) {
    Map<String, String> entries = new TreeMap<>();
    try (Cursor cursor = db.scan(null, null)) {
      for (; cursor.isValid(); cursor.next()) {
        entries.put(text(cursor.key()), text(cursor.value()));
      }
    }
    return entries;
  }

  /** {@code count} SYNC puts of {@code valueBytes}-byte values to distinct keys. */
  private static Script puts(int count, int valueBytes) {
    return (db, ledger) -> {
      for (int i = 0; i < count; i++) {
        ledger.put(db, "key-" + i, String.valueOf((char) ('a' + i)).repeat(valueBytes));
      }
    };
  }

  private static byte[] ascii(String text) {
    return text.getBytes(StandardCharsets.US_ASCII);
  }

  private static String text(byte[] bytes) {
    return bytes == null ? null : new String(bytes, StandardCharsets.US_ASCII);
  }

  /** What a workload does with an open engine. */
  @FunctionalInterface
  private interface Script {
    void run(Shale db, Ledger ledger) throws IOException;
  }

  /**
   * One scripted workload.
   *
   * @param name for failure messages
   * @param options the engine's options (the env is set per run)
   * @param script run under the fault-injecting env, crashed at each operation in turn
   * @param prepare run first, fault-free on the real file system, or null
   * @param failFirst {kind, suffix} of an operation to fail with an IOException, or null
   */
  private record Workload(
      String name, ShaleOptions options, Script script, Script prepare, String[] failFirst) {

    Workload(String name, ShaleOptions options, Script script) {
      this(name, options, script, null, null);
    }

    Workload failingFirst(String kind, String suffix) {
      return new Workload(name, options, script, prepare, new String[] {kind, suffix});
    }

    Workload preparedBy(Script setup) {
      return new Workload(name, options, script, setup, failFirst);
    }
  }

  /** Crash at {@code operation}, then lose what {@code drop} and {@code entries} say. */
  private record CrashPoint(long operation, DropMode drop, EntryLoss entries) {}

  /** One run's directory, env and ledger. */
  private record Run(Path dir, FaultInjectionEnv env, Ledger ledger) {

    void execute(Workload workload) throws IOException {
      try (Shale db =
          Shale.open(dir, workload.options().withEnv(env), Clock.system(), Metrics.NOOP)) {
        workload.script().run(db, ledger);
      } catch (EngineStateException failed) {
        // the injected failure stopped the writes; what was acknowledged before is in the ledger
      }
    }
  }

  /** Every value attempted per key, and the value of each acknowledged write. */
  private static final class Ledger {
    private final Map<String, Set<String>> attempted = new HashMap<>();
    private final Map<String, String> acknowledged = new LinkedHashMap<>();

    void put(Shale db, String key, String value) {
      attempted.computeIfAbsent(key, k -> new HashSet<>()).add(value);
      db.put(ascii(key), ascii(value), Durability.SYNC);
      acknowledged.put(key, value);
    }

    Map<String, Set<String>> attempted() {
      return attempted;
    }

    Map<String, String> acknowledged() {
      return acknowledged;
    }
  }
}
