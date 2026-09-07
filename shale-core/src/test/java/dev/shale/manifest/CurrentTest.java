package dev.shale.manifest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.shale.CorruptionException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The CURRENT file: the pointer that makes discovery atomic (format.md §5). */
class CurrentTest {

  @TempDir private Path dir;

  @Test
  void noCurrentAndNoManifest_isANewDatabase() throws IOException {
    assertThat(Current.read(dir)).isEmpty();
  }

  @Test
  void writeThenRead_namesTheManifest() throws IOException {
    Files.createFile(dir.resolve("000007.manifest"));

    Current.write(dir, "000007.manifest");

    assertThat(Current.read(dir)).contains(dir.resolve("000007.manifest"));
  }

  @Test
  void currentNamingAMissingManifest_isCorruptionNotAnEmptyDatabase() throws IOException {
    // The distinction that matters: "I have no database" and "my database is gone" must not
    // look the same, or a lost CURRENT target silently becomes a fresh, empty engine (N4).
    Files.writeString(dir.resolve("CURRENT"), "000042.manifest\n", StandardCharsets.UTF_8);

    assertThatThrownBy(() -> Current.read(dir))
        .isInstanceOf(CorruptionException.class)
        .hasMessageContaining("000042.manifest");
  }

  @Test
  void writingCurrent_replacesItAtomicallyAndLeavesNoTempFile() throws IOException {
    Files.createFile(dir.resolve("000001.manifest"));
    Files.createFile(dir.resolve("000002.manifest"));
    Current.write(dir, "000001.manifest");

    Current.write(dir, "000002.manifest");

    assertThat(Current.read(dir)).contains(dir.resolve("000002.manifest"));
    assertThat(Files.exists(dir.resolve("CURRENT.tmp"))).isFalse();
  }

  @Test
  void aStrayTempFileFromACrashedRewrite_isIgnored() throws IOException {
    Files.createFile(dir.resolve("000003.manifest"));
    Current.write(dir, "000003.manifest");
    Files.writeString(dir.resolve("CURRENT.tmp"), "000099.manifest\n", StandardCharsets.UTF_8);

    // A crash between writing the temp file and renaming it leaves the old CURRENT in force.
    // That is the whole point of the temp-then-rename: the half-written pointer is never read.
    assertThat(Current.read(dir)).contains(dir.resolve("000003.manifest"));
  }

  @Test
  void anEmptyOrGarbledCurrent_isCorruption() throws IOException {
    Files.writeString(dir.resolve("CURRENT"), "", StandardCharsets.UTF_8);

    assertThatThrownBy(() -> Current.read(dir)).isInstanceOf(CorruptionException.class);
  }

  @Test
  void currentNamingSomethingOutsideTheDirectory_isRefused() throws IOException {
    // A manifest name is a file name, never a path: "../../etc/passwd" is not a database.
    Files.writeString(dir.resolve("CURRENT"), "../escape.manifest\n", StandardCharsets.UTF_8);

    assertThatThrownBy(() -> Current.read(dir))
        .isInstanceOf(CorruptionException.class)
        .hasMessageContaining("file name");
  }

  @Test
  void readReturnsOptional_soCallersCannotForgetTheNewDatabaseCase() throws IOException {
    Optional<Path> current = Current.read(dir);

    assertThat(current).isEmpty();
  }
}
