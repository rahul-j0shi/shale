package dev.shale.manifest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.shale.CorruptionException;
import dev.shale.env.Env;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CurrentFileTest {

  @TempDir private Path dir;

  @Test
  void noCurrent_isEmpty() throws IOException {
    assertThat(CurrentFile.read(Env.DEFAULT, dir)).isEmpty();
  }

  @Test
  void write_thenRead_namesTheManifest_andLeavesNoTempFile() throws IOException {
    CurrentFile.write(Env.DEFAULT, dir, 7);

    assertThat(Files.readString(dir.resolve("CURRENT"))).isEqualTo("MANIFEST-000007\n");
    assertThat(CurrentFile.read(Env.DEFAULT, dir)).contains("MANIFEST-000007");
    assertThat(dir.resolve("CURRENT.tmp")).doesNotExist();
  }

  @Test
  void write_replacesThePreviousManifest() throws IOException {
    CurrentFile.write(Env.DEFAULT, dir, 7);
    CurrentFile.write(Env.DEFAULT, dir, 1_000_000);

    assertThat(CurrentFile.read(Env.DEFAULT, dir)).contains("MANIFEST-1000000");
  }

  @Test
  void aMissingNewline_isCorruptionNamingCurrent() throws IOException {
    assertCorrupt("MANIFEST-000007");
  }

  @Test
  void garbage_isCorruption() throws IOException {
    assertCorrupt("");
    assertCorrupt("MANIFEST-7\n"); // fewer than six digits: no engine wrote it
    assertCorrupt("MANIFEST-000007\nMANIFEST-000008\n");
    assertCorrupt("\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0");
    assertCorrupt("MANIFEST-99999999999999999999\n"); // too large for any number allocated
  }

  @Test
  void manifestNumber_parsesOnlyManifestNames() {
    assertThat(CurrentFile.manifestNumber("MANIFEST-000012")).contains(12L);
    assertThat(CurrentFile.manifestNumber("MANIFEST-1000000")).contains(1_000_000L);
    assertThat(CurrentFile.manifestNumber("MANIFEST-12")).isEmpty();
    assertThat(CurrentFile.manifestNumber("000012.sst")).isEmpty();
  }

  private void assertCorrupt(String content) throws IOException {
    Files.write(dir.resolve("CURRENT"), content.getBytes(StandardCharsets.ISO_8859_1));

    assertThatThrownBy(() -> CurrentFile.read(Env.DEFAULT, dir))
        .as("CURRENT = %s", content.replace("\n", "\\n"))
        .isExactlyInstanceOf(CorruptionException.class)
        .hasMessageContaining("CURRENT");
  }
}
