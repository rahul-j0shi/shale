package dev.shale.env;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.shale.EngineStateException;
import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The behaviour every {@link Env} must have, checked against {@link Env#DEFAULT}. A fault-injecting
 * env (M5 Step 3) wraps this one, so these are also the semantics it starts from.
 */
class EnvContractTest {

  private final Env env = Env.DEFAULT;

  @TempDir private Path dir;

  @Test
  void newWritableFile_whenTheFileExists_fails() throws IOException {
    Path path = dir.resolve("a");
    env.newWritableFile(path).close();

    assertThatThrownBy(() -> env.newWritableFile(path))
        .isInstanceOf(FileAlreadyExistsException.class);
  }

  @Test
  void appendThenRead_returnsTheBytesAppended() throws IOException {
    Path path = dir.resolve("a");
    try (WritableFile file = env.newWritableFile(path)) {
      file.append(ascii("hello "));
      file.append(ascii("xxworldxx"), 2, 5);
      file.sync();
      assertThat(file.size()).isEqualTo(11);
    }

    assertThat(env.readAllBytes(path)).isEqualTo(ascii("hello world"));
    try (ReadableFile file = env.newReadableFile(path)) {
      assertThat(file.size()).isEqualTo(11);
      byte[] buffer = new byte[8];
      assertThat(file.read(6, buffer, 1, 5)).isEqualTo(5);
      assertThat(new String(buffer, 1, 5, StandardCharsets.US_ASCII)).isEqualTo("world");
    }
  }

  @Test
  void read_pastTheEnd_returnsOnlyWhatExists() throws IOException {
    Path path = dir.resolve("a");
    try (WritableFile file = env.newWritableFile(path)) {
      file.append(ascii("abc"));
    }
    try (ReadableFile file = env.newReadableFile(path)) {
      byte[] buffer = new byte[10];
      assertThat(file.read(1, buffer, 0, 10)).isEqualTo(2);
      assertThat(file.read(3, buffer, 0, 10)).isZero();
    }
  }

  @Test
  void rename_replacesTheTargetAtomically() throws IOException {
    write("from", "new");
    write("to", "old");

    env.rename(dir.resolve("from"), dir.resolve("to"));
    env.syncDirectory(dir);

    assertThat(env.exists(dir.resolve("from"))).isFalse();
    assertThat(env.readAllBytes(dir.resolve("to"))).isEqualTo(ascii("new"));
  }

  @Test
  void delete_removesTheFile_andFailsIfItIsMissing() throws IOException {
    write("a", "x");

    env.delete(dir.resolve("a"));

    assertThat(env.exists(dir.resolve("a"))).isFalse();
    assertThatThrownBy(() -> env.delete(dir.resolve("a"))).isInstanceOf(NoSuchFileException.class);
  }

  @Test
  void children_namesEveryEntry() throws IOException {
    write("000001.wal", "x");
    write("CURRENT", "y");
    env.createDirectories(dir.resolve("sub/deeper"));

    assertThat(env.children(dir)).containsExactlyInAnyOrder("000001.wal", "CURRENT", "sub");
  }

  @Test
  void lock_heldTwice_fails_andCanBeTakenAgainOnceReleased() throws IOException {
    Path lockFile = dir.resolve("LOCK");
    Closeable held = env.lock(lockFile);

    assertThatThrownBy(() -> env.lock(lockFile))
        .isExactlyInstanceOf(EngineStateException.class)
        .hasMessageContaining("in use");

    held.close();
    env.lock(lockFile).close();
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
