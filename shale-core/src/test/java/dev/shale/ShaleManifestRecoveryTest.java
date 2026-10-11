package dev.shale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.shale.env.Env;
import dev.shale.env.FaultInjectionEnv;
import dev.shale.manifest.CurrentFile;
import dev.shale.manifest.ManifestState;
import dev.shale.manifest.ManifestWriter;
import dev.shale.manifest.VersionSet;
import dev.shale.wal.WalReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Recovery through the manifest (ADR-0012 §2.3–2.6; M5 Step 7): the manifest, not the directory,
 * says which tables are live; damage fails loudly, naming the file; a failed flush stops writes but
 * not reads; and nothing a refused open touches is changed. Includes the review's regressions for
 * SH-01, SH-02 and SH-10.
 */
class ShaleManifestRecoveryTest {

  private static final Path GOLDEN = Path.of("src/test/resources/golden");

  @TempDir private Path root;

  // ------------------------------------------------------------------ the manifest's authority

  @Test
  void reopeningWithoutWrites_aThousandTimes_changesNothing() throws IOException {
    Path dir = root.resolve("db");
    try (Shale db = Shale.open(dir, Clock.system(), Metrics.NOOP, 64)) {
      db.put(ascii("k"), new byte[100], Durability.SYNC); // one flushed table
      db.put(ascii("k"), ascii("v"), Durability.SYNC); // and a write still in the WAL
    }
    try (Shale db = Shale.open(dir, Clock.system(), Metrics.NOOP)) {
      assertThat(db.get(ascii("k"))).isEqualTo(ascii("v"));
    }
    List<String> tables = names(dir, ".sst");

    for (int reopen = 0; reopen < 1000; reopen++) {
      Shale.open(dir, Clock.system(), Metrics.NOOP).close();
    }

    try (Shale db = Shale.open(dir, Clock.system(), Metrics.NOOP)) {
      assertThat(db.get(ascii("k"))).isEqualTo(ascii("v"));
    }
    assertThat(names(dir, ".sst")).as("no table added or lost").isEqualTo(tables);
    assertThat(names(dir, ".wal")).hasSize(1);
    assertThat(names(dir, "")).filteredOn(n -> n.startsWith("MANIFEST-")).hasSize(1);
  }

  @Test
  void aTableTheManifestNames_missing_isCorruptionNamingIt() throws IOException {
    Path dir = databaseWithOneTable();
    String table = names(dir, ".sst").getFirst();
    Files.delete(dir.resolve(table));

    assertThatThrownBy(() -> Shale.open(dir, Clock.system(), Metrics.NOOP))
        .isExactlyInstanceOf(CorruptionException.class)
        .hasMessageContaining("references missing table " + table)
        .hasMessageContaining("MANIFEST-");
  }

  @Test
  void aGarbageCurrent_isCorruption() throws IOException {
    Path dir = databaseWithOneTable();
    Files.writeString(dir.resolve("CURRENT"), "not a manifest name");

    assertThatThrownBy(() -> Shale.open(dir, Clock.system(), Metrics.NOOP))
        .isExactlyInstanceOf(CorruptionException.class)
        .hasMessageContaining("CURRENT");
  }

  @Test
  void aManifestWrittenWithAnotherComparator_refusesToOpen() throws IOException {
    Path dir = databaseWithOneTable();
    ManifestState state = VersionSet.readState(Env.DEFAULT, dir).orElseThrow();
    ManifestState other =
        new ManifestState(
            Optional.of("other.ReverseComparator"),
            state.logNumber(),
            state.nextFileNumber() + 1,
            state.lastSequence(),
            state.files());
    try (ManifestWriter manifest =
        ManifestWriter.create(Env.DEFAULT, dir.resolve(CurrentFile.manifestName(999)))) {
      manifest.log(other.toEdit());
    }
    CurrentFile.write(Env.DEFAULT, dir, 999);

    assertThatThrownBy(() -> Shale.open(dir, Clock.system(), Metrics.NOOP))
        .isExactlyInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("other.ReverseComparator");
  }

  @Test
  void anM4Directory_opensWithEveryRecord_andIsMigratedToAManifest() throws IOException {
    Path dir = root.resolve("m4");
    copyDirectory(GOLDEN.resolve("db/m4-layout"), dir);

    try (Shale db = Shale.open(dir, Clock.system(), Metrics.NOOP)) {
      for (int i = 1; i <= 7; i++) {
        byte[] expected = i == 2 ? null : ascii("value-" + i + "-".repeat(20));
        assertThat(db.get(ascii("key-" + i))).as("key-%d", i).isEqualTo(expected);
      }
    }

    assertThat(dir.resolve("CURRENT")).exists();
    for (String segment : names(dir, ".wal")) {
      assertThat(WalReader.read(dir.resolve(segment)).records())
          .as("nothing left to replay in %s", segment)
          .isEmpty();
    }
  }

  @Test
  void anOrphanTable_isDeletedAtOpen() throws IOException {
    Path dir = databaseWithOneTable();
    String live = names(dir, ".sst").getFirst();
    Files.copy(dir.resolve(live), dir.resolve("000500.sst")); // valid, but no manifest names it

    try (Shale db = Shale.open(dir, Clock.system(), Metrics.NOOP)) {
      assertThat(db.get(ascii("k"))).isNotNull();
    }

    assertThat(dir.resolve("000500.sst")).doesNotExist();
    assertThat(dir.resolve(live)).exists();
  }

  @Test
  void manifestRollover_keepsEveryFile() throws IOException {
    Path dir = root.resolve("db");
    RecordingMetrics metrics = new RecordingMetrics();
    ShaleOptions options =
        ShaleOptions.defaults().withWriteBufferSizeBytes(200).withManifestRolloverBytes(1024);
    try (Shale db = Shale.open(dir, options, Clock.system(), metrics)) {
      for (int i = 0; i < 60; i++) {
        db.put(ascii(String.format("key-%03d", i)), new byte[60], Durability.NONE);
      }
    }
    assertThat(metrics.counter("manifest.rollover.count")).isPositive();
    assertThat(names(dir, "")).filteredOn(n -> n.startsWith("MANIFEST-")).hasSize(1);

    try (Shale db = Shale.open(dir, options, Clock.system(), Metrics.NOOP)) {
      for (int i = 0; i < 60; i++) {
        assertThat(db.get(ascii(String.format("key-%03d", i)))).as("key-%03d", i).hasSize(60);
      }
    }
  }

  // ------------------------------------------------------------------ the failed state (SH-01)

  @Test
  void aFlushThatFails_stopsWrites_keepsReads_andReopenRecoversEveryAck() throws IOException {
    Path runRoot = root.resolve("run");
    Files.createDirectories(runRoot);
    FaultInjectionEnv env = new FaultInjectionEnv(runRoot);
    Path dir = runRoot.resolve("db");
    ShaleOptions options = ShaleOptions.defaults().withWriteBufferSizeBytes(1000).withEnv(env);
    long tableCreate = firstOperation("create", ".sst.tmp", options, dir, root.resolve("dry"));
    env.failOn(tableCreate, new IOException("no space left on device"));

    try (Shale db = Shale.open(dir, options, Clock.system(), Metrics.NOOP)) {
      db.put(ascii("A"), filled('a', 100), Durability.SYNC);
      db.put(ascii("B"), filled('b', 100), Durability.SYNC);
      db.put(ascii("C"), filled('c', 2000), Durability.SYNC); // fills it: the flush fails

      assertThatThrownBy(() -> db.put(ascii("A"), ascii("new"), Durability.SYNC))
          .isExactlyInstanceOf(EngineStateException.class)
          .hasMessageContaining("reopen to recover")
          .hasRootCauseMessage("no space left on device");
      assertReadsAgree(db, Map.of("A", filled('a', 100), "B", filled('b', 100)));
      assertThat(db.get(ascii("C"))).isEqualTo(filled('c', 2000));
    }

    try (Shale db = Shale.open(dir, options.withEnv(Env.DEFAULT), Clock.system(), Metrics.NOOP)) {
      assertReadsAgree(db, Map.of("A", filled('a', 100), "B", filled('b', 100)));
      assertThat(db.get(ascii("C"))).as("the write that triggered the flush").hasSize(2000);
      db.put(ascii("A"), ascii("new"), Durability.SYNC); // the reopened engine writes again
      assertThat(db.get(ascii("A"))).isEqualTo(ascii("new"));
    }
  }

  // ------------------------------------------------------------------ released resources

  @Test
  void sh02_aRefusedOpen_releasesEveryTableItOpened() throws IOException {
    Path runRoot = root.resolve("run");
    Files.createDirectories(runRoot);
    Path dir = runRoot.resolve("db");
    try (Shale db = Shale.open(dir, Clock.system(), Metrics.NOOP, 64)) {
      db.put(ascii("a"), new byte[100], Durability.SYNC); // table one
      db.put(ascii("b"), new byte[100], Durability.SYNC); // table two
    }
    List<String> tables = names(dir, ".sst");
    assertThat(tables).hasSize(2);
    Path newest = dir.resolve(tables.getLast());
    Files.write(newest, new byte[] {1, 2, 3}); // too short for a footer: refused at open

    FaultInjectionEnv env = new FaultInjectionEnv(runRoot);
    ShaleOptions options = ShaleOptions.defaults().withEnv(env);
    for (int attempt = 0; attempt < 10; attempt++) {
      assertThatThrownBy(() -> Shale.open(dir, options, Clock.system(), Metrics.NOOP))
          .isExactlyInstanceOf(CorruptionException.class)
          .hasMessageContaining(newest.getFileName().toString());
      assertThat(env.openHandles()).as("after refused open %d", attempt).isZero();
    }
  }

  @Test
  void sh10_scansThatFailOnADamagedBlock_leakNothing() throws IOException {
    Path runRoot = root.resolve("run");
    Files.createDirectories(runRoot);
    FaultInjectionEnv env = new FaultInjectionEnv(runRoot);
    Path dir = runRoot.resolve("db");
    ShaleOptions options = ShaleOptions.defaults().withWriteBufferSizeBytes(20_000).withEnv(env);
    try (Shale db = Shale.open(dir, options, Clock.system(), Metrics.NOOP)) {
      for (int i = 0; i < 400; i++) { // the first ~300 keys fill one multi-block table
        db.put(ascii(String.format("key-%03d", i)), new byte[50], Durability.NONE);
      }
    }
    Path table = dir.resolve(names(dir, ".sst").getFirst());
    byte[] bytes = Files.readAllBytes(table);
    bytes[10] ^= 1; // inside the first data block
    Files.write(table, bytes);

    Shale db = Shale.open(dir, options, Clock.system(), Metrics.NOOP);
    for (int scan = 0; scan < 10; scan++) {
      assertThatThrownBy(() -> db.scan(null, null).close())
          .isInstanceOf(CorruptionException.class)
          .hasMessageContaining(table.getFileName().toString());
    }
    try (Cursor later = db.scan(ascii("key-200"), ascii("key-203"))) {
      assertThat(later.isValid()).as("an undamaged range still reads").isTrue();
    }
    db.close();

    assertThat(env.openHandles()).as("every reference given back").isZero();
  }

  // ------------------------------------------------------------------ refused opens change nothing

  @Test
  void aCorruptManifestRecord_refusesTheOpen_andChangesNothing() throws IOException {
    Path dir = databaseWithOneTable();
    Path manifest = dir.resolve(CurrentFile.read(Env.DEFAULT, dir).orElseThrow());
    byte[] bytes = Files.readAllBytes(manifest);
    bytes[30] ^= 1; // inside the first edit's payload
    Files.write(manifest, bytes);

    assertRejectedOpenChangesNothing(dir, "manifest");
  }

  @Test
  void anUnverifiableV1WalTail_besideAManifest_refusesTheOpen_andChangesNothing()
      throws IOException {
    Path dir = databaseWithOneTable();
    String segment = names(dir, ".wal").getLast(); // the one the manifest still needs
    byte[] v1 = Files.readAllBytes(GOLDEN.resolve("wal/v1/two-puts.wal"));
    v1[21] ^= 1 << 6; // SH-18: a length bit
    Files.write(dir.resolve(segment), v1);

    assertRejectedOpenChangesNothing(dir, "unverifiable WAL tail");
  }

  // ------------------------------------------------------------------ helpers

  /** A closed database holding one flushed table with key {@code k}, and a manifest. */
  private Path databaseWithOneTable() throws IOException {
    Path dir = root.resolve("db");
    try (Shale db = Shale.open(dir, Clock.system(), Metrics.NOOP, 64)) {
      db.put(ascii("k"), new byte[100], Durability.SYNC);
    }
    assertThat(names(dir, ".sst")).hasSize(1);
    return dir;
  }

  /**
   * The index of the first operation whose trace line starts with {@code kind} and contains {@code
   * suffix}, found by running the SH-01 workload once without faults in {@code dryRoot}.
   */
  private static long firstOperation(
      String kind, String suffix, ShaleOptions options, Path dir, Path dryRoot) throws IOException {
    Files.createDirectories(dryRoot);
    FaultInjectionEnv dry = new FaultInjectionEnv(dryRoot);
    try (Shale db =
        Shale.open(
            dryRoot.resolve(dir.getFileName()),
            options.withEnv(dry),
            Clock.system(),
            Metrics.NOOP)) {
      db.put(ascii("A"), filled('a', 100), Durability.SYNC);
      db.put(ascii("B"), filled('b', 100), Durability.SYNC);
      db.put(ascii("C"), filled('c', 2000), Durability.SYNC);
    }
    for (String line : dry.trace()) {
      String[] parts = line.split(" ");
      if (parts[1].equals(kind) && parts[2].endsWith(suffix)) {
        return Long.parseLong(parts[0]);
      }
    }
    throw new AssertionError("no " + kind + " *" + suffix + " in " + dry.trace());
  }

  private static void assertReadsAgree(Shale db, Map<String, byte[]> expected) {
    Map<String, byte[]> scanned = new TreeMap<>();
    try (Cursor cursor = db.scan(null, null)) {
      for (; cursor.isValid(); cursor.next()) {
        scanned.put(new String(cursor.key(), StandardCharsets.US_ASCII), cursor.value());
      }
    }
    for (Map.Entry<String, byte[]> entry : expected.entrySet()) {
      assertThat(db.get(ascii(entry.getKey())))
          .as("get %s", entry.getKey())
          .isEqualTo(entry.getValue());
      assertThat(scanned.get(entry.getKey()))
          .as("scan agrees with get on %s", entry.getKey())
          .isEqualTo(entry.getValue());
    }
  }

  private static void assertRejectedOpenChangesNothing(Path dir, String reason) throws IOException {
    Map<String, String> before = snapshot(dir);

    assertThatThrownBy(() -> Shale.open(dir, Clock.system(), Metrics.NOOP))
        .isExactlyInstanceOf(CorruptionException.class)
        .hasMessageContaining(reason);

    assertThat(snapshot(dir)).as("every file name and its bytes").isEqualTo(before);
  }

  /** File name → SHA-256, for every file but {@code *.tmp} and the empty {@code LOCK}. */
  private static Map<String, String> snapshot(Path dir) throws IOException {
    Map<String, String> files = new TreeMap<>();
    for (String name : names(dir, "")) {
      if (!name.endsWith(".tmp") && !name.equals("LOCK")) {
        files.put(name, sha256(Files.readAllBytes(dir.resolve(name))));
      }
    }
    return files;
  }

  private static List<String> names(Path dir, String suffix) throws IOException {
    try (Stream<Path> files = Files.list(dir)) {
      return files
          .map(path -> path.getFileName().toString())
          .filter(name -> name.endsWith(suffix))
          .sorted()
          .toList();
    }
  }

  private static void copyDirectory(Path from, Path to) throws IOException {
    Files.createDirectories(to);
    try (Stream<Path> files = Files.list(from)) {
      for (Path file : files.toList()) {
        Files.copy(file, to.resolve(file.getFileName()));
      }
    }
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static byte[] filled(char c, int length) {
    return String.valueOf(c).repeat(length).getBytes(StandardCharsets.US_ASCII);
  }

  private static byte[] ascii(String text) {
    return text.getBytes(StandardCharsets.US_ASCII);
  }
}
