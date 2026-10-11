package dev.shale.env;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link FaultInjectionEnv} simulates exactly what POSIX lets a power loss take: unsynced bytes and
 * unsynced directory entries, and nothing else. If this harness lost too little, the crash matrix
 * would pass on an engine that is not durable; too much, and it would fail one that is.
 */
class FaultInjectionEnvTest {

  @TempDir private Path root;
  private Path dir;
  private FaultInjectionEnv env;

  @BeforeEach
  void setUp() throws IOException {
    dir = root.resolve("db");
    Files.createDirectories(dir);
    env = new FaultInjectionEnv(root);
  }

  @Test
  void unsyncedAppends_vanish_syncedOnesStay() throws IOException {
    try (WritableFile file = env.newWritableFile(dir.resolve("a"))) {
      file.append(ascii("durable"));
      file.sync();
      file.append(ascii("-lost"));
    }
    env.syncDirectory(dir);

    env.dropUnsyncedData();

    assertThat(Files.readAllBytes(dir.resolve("a"))).isEqualTo(ascii("durable"));
  }

  @Test
  void anUnsyncedCreate_isUndone_aSyncedOneStays() throws IOException {
    write("synced");
    env.syncDirectory(dir);
    write("unsynced");

    env.dropUnsyncedData();

    assertThat(env.children(dir)).containsExactly("synced");
  }

  @Test
  void anUnsyncedRename_isUndone_andTheFileItReplacedComesBack() throws IOException {
    write("CURRENT", "old");
    write("CURRENT.tmp", "new");
    env.syncDirectory(dir);
    env.rename(dir.resolve("CURRENT.tmp"), dir.resolve("CURRENT"));

    env.dropUnsyncedData();

    assertThat(env.children(dir)).containsExactlyInAnyOrder("CURRENT", "CURRENT.tmp");
    assertThat(Files.readAllBytes(dir.resolve("CURRENT"))).isEqualTo(ascii("old"));
    assertThat(Files.readAllBytes(dir.resolve("CURRENT.tmp"))).isEqualTo(ascii("new"));
  }

  @Test
  void aSyncedRename_stays() throws IOException {
    write("CURRENT", "old");
    write("CURRENT.tmp", "new");
    env.syncDirectory(dir);
    env.rename(dir.resolve("CURRENT.tmp"), dir.resolve("CURRENT"));
    env.syncDirectory(dir);

    env.dropUnsyncedData();

    assertThat(env.children(dir)).containsExactly("CURRENT");
    assertThat(Files.readAllBytes(dir.resolve("CURRENT"))).isEqualTo(ascii("new"));
  }

  @Test
  void aCreateAndRenameWithNoDirectorySync_leaveNothing() throws IOException {
    // A flush's table: written as .tmp, synced, renamed — but the directory never synced.
    write("000005.sst.tmp", "table");
    env.rename(dir.resolve("000005.sst.tmp"), dir.resolve("000005.sst"));

    env.dropUnsyncedData();

    assertThat(env.children(dir)).isEmpty();
  }

  @Test
  void aDeleteNotFollowedByADirectorySync_isUndone() throws IOException {
    write("000001.wal", "segment");
    env.syncDirectory(dir);
    env.delete(dir.resolve("000001.wal"));
    assertThat(env.children(dir)).isEmpty();

    env.dropUnsyncedData();

    assertThat(Files.readAllBytes(dir.resolve("000001.wal"))).isEqualTo(ascii("segment"));
  }

  @Test
  void aSyncedDelete_stays() throws IOException {
    write("000001.wal", "segment");
    env.syncDirectory(dir);
    env.delete(dir.resolve("000001.wal"));
    env.syncDirectory(dir);

    env.dropUnsyncedData();

    assertThat(env.children(dir)).isEmpty();
  }

  @Test
  void crashAt_throwsAtExactlyThatOperation_andAtEveryOneAfter() throws IOException {
    WritableFile file = env.newWritableFile(dir.resolve("a")); // operation 0
    file.append(ascii("x")); // 1
    env.crashAt(2);

    assertThatThrownBy(file::sync).isInstanceOf(SimulatedCrash.class).hasMessageContaining("2");
    assertThat(env.crashed()).isTrue();
    assertThatThrownBy(() -> file.append(ascii("y"))).isInstanceOf(SimulatedCrash.class);
    assertThatThrownBy(() -> env.children(dir)).isInstanceOf(SimulatedCrash.class);
    assertThat(env.trace()).containsExactly("0 create a", "1 append a 1", "2 sync a");

    env.dropUnsyncedData();

    assertThat(env.crashed()).isFalse();
    assertThat(env.children(dir)).as("the create was never made durable").isEmpty();
  }

  @Test
  void failOn_throwsThatIOExceptionOnce_andLaterOperationsRun() throws IOException {
    IOException diskFull = new IOException("no space left on device");
    env.failOn(1, diskFull);
    try (WritableFile file = env.newWritableFile(dir.resolve("a"))) {
      assertThatThrownBy(() -> file.append(ascii("x"))).isSameAs(diskFull);
      file.append(ascii("y"));
      file.sync();
    }

    assertThat(Files.readAllBytes(dir.resolve("a"))).isEqualTo(ascii("y"));
    assertThat(env.crashed()).isFalse();
  }

  @Test
  void tornMode_keepsASeededPrefixOfTheUnsyncedTail() throws IOException {
    byte[] tail = ascii("0123456789abcdefghijklmnopqrstuvwxyz");
    int[] kept = new int[2];
    for (int run = 0; run < 2; run++) {
      Path runDir = root.resolve("run" + run);
      Files.createDirectories(runDir);
      FaultInjectionEnv fresh = new FaultInjectionEnv(root);
      try (WritableFile file = fresh.newWritableFile(runDir.resolve("a"))) {
        file.append(ascii("synced"));
        file.sync();
        file.append(tail);
      }
      fresh.syncDirectory(runDir);

      fresh.dropUnsyncedData(FaultInjectionEnv.DropMode.TORN, 42);

      byte[] left = Files.readAllBytes(runDir.resolve("a"));
      assertThat(left.length).isBetween(6, 6 + tail.length);
      assertThat(new String(left, StandardCharsets.US_ASCII))
          .isEqualTo(
              ("synced" + new String(tail, StandardCharsets.US_ASCII)).substring(0, left.length));
      kept[run] = left.length;
    }
    assertThat(kept[0]).as("the same seed keeps the same prefix").isEqualTo(kept[1]);
  }

  @Test
  void openHandles_risesOnOpen_andReturnsToItsBaselineOnClose() throws IOException {
    write("a", "x");
    assertThat(env.openHandles()).isZero();

    WritableFile writable = env.newWritableFile(dir.resolve("b"));
    ReadableFile readable = env.newReadableFile(dir.resolve("a"));
    Closeable lock = env.lock(dir.resolve("LOCK"));
    assertThat(env.openHandles()).isEqualTo(3);

    writable.close();
    readable.close();
    readable.close(); // idempotent: counted once
    lock.close();
    assertThat(env.openHandles()).isZero();
  }

  @Test
  void aPowerLoss_closesTheDeadProcesssHandles_andReleasesItsLock() throws IOException {
    env.lock(dir.resolve("LOCK"));
    env.newWritableFile(dir.resolve("a"));

    env.dropUnsyncedData();

    assertThat(env.openHandles()).isZero();
    env.lock(dir.resolve("LOCK")).close(); // a reopening engine can take it again
  }

  private void write(String name) throws IOException {
    write(name, name);
  }

  private void write(String name, String content) throws IOException {
    try (WritableFile file = env.newWritableFile(dir.resolve(name))) {
      file.append(ascii(content));
      file.sync();
    }
  }

  private static byte[] ascii(String text) {
    return text.getBytes(StandardCharsets.US_ASCII);
  }
}
