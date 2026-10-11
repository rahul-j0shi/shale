package dev.shale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The engine's lifecycle (ADR-0012 §2.6, M5 Step 4): {@code close()} is idempotent, everything
 * after it is rejected with {@link EngineStateException}, a cursor opened before it keeps reading,
 * and {@code LOCK} keeps a second engine out of an open directory.
 */
class ShaleLifecycleTest {

  @TempDir private Path dir;

  @Test
  void close_twice_isANoOp() throws IOException {
    Shale db = Shale.open(dir, Clock.system(), Metrics.NOOP);
    db.put(ascii("k"), ascii("v"), Durability.SYNC);

    db.close();
    db.close();

    try (Shale reopened = Shale.open(dir, Clock.system(), Metrics.NOOP)) {
      assertThat(reopened.get(ascii("k"))).isEqualTo(ascii("v"));
    }
  }

  @Test
  void everyOperationAfterClose_throwsEngineStateException() throws IOException {
    Shale db = Shale.open(dir, Clock.system(), Metrics.NOOP);
    db.close();

    assertClosed(() -> db.put(ascii("k"), ascii("v"), Durability.NONE));
    assertClosed(() -> db.delete(ascii("k"), Durability.NONE));
    assertClosed(() -> db.get(ascii("k")));
    assertClosed(() -> db.scan(ascii("a"), ascii("z")).close());
  }

  @Test
  void aCursorOpenedBeforeClose_keepsReading() throws IOException {
    // 64-byte buffer: the second put flushes, so the cursor reads a table as well as memtables.
    Shale db = Shale.open(dir, Clock.system(), Metrics.NOOP, 64);
    db.put(ascii("a"), new byte[80], Durability.SYNC);
    db.put(ascii("b"), ascii("vb"), Durability.SYNC);
    try (Cursor cursor = db.scan(ascii("a"), ascii("z"))) {
      db.close();

      assertThat(cursor.isValid()).isTrue();
      assertThat(cursor.key()).isEqualTo(ascii("a"));
      cursor.next();
      assertThat(cursor.value()).isEqualTo(ascii("vb"));
      cursor.next();
      assertThat(cursor.isValid()).isFalse();
    }
  }

  @Test
  void aSecondOpenOfTheSameDirectory_isRefused_untilTheFirstCloses() throws IOException {
    try (Shale first = Shale.open(dir, Clock.system(), Metrics.NOOP)) {
      first.put(ascii("k"), ascii("v"), Durability.SYNC);

      assertThatThrownBy(() -> Shale.open(dir, Clock.system(), Metrics.NOOP))
          .isExactlyInstanceOf(EngineStateException.class)
          .hasMessageContaining("database directory is in use");
      assertThat(first.get(ascii("k")))
          .as("the refused open touched nothing")
          .isEqualTo(ascii("v"));
    }
    try (Shale second = Shale.open(dir, Clock.system(), Metrics.NOOP)) {
      assertThat(second.get(ascii("k"))).isEqualTo(ascii("v"));
    }
  }

  @Test
  void aRefusedOpen_releasesTheLock() throws IOException {
    Shale.open(dir, Clock.system(), Metrics.NOOP).close();
    byte[] current = Files.readAllBytes(dir.resolve("CURRENT"));
    Files.write(dir.resolve("CURRENT"), new byte[] {1, 2, 3}); // names no manifest

    assertThatThrownBy(() -> Shale.open(dir, Clock.system(), Metrics.NOOP))
        .isInstanceOf(CorruptionException.class);

    Files.write(dir.resolve("CURRENT"), current);
    Shale.open(dir, Clock.system(), Metrics.NOOP).close(); // the lock was not left held
  }

  private static void assertClosed(ThrowingCallable call) {
    assertThatThrownBy(call)
        .isExactlyInstanceOf(EngineStateException.class)
        .hasMessageContaining("engine is closed");
  }

  private static byte[] ascii(String text) {
    return text.getBytes(StandardCharsets.US_ASCII);
  }
}
