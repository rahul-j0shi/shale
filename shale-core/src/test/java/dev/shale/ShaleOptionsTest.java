package dev.shale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.shale.env.Env;
import org.junit.jupiter.api.Test;

class ShaleOptionsTest {

  @Test
  void defaults_areLevelDbs() {
    ShaleOptions options = ShaleOptions.defaults();

    assertThat(options.writeBufferSizeBytes()).isEqualTo(4L * 1024 * 1024);
    assertThat(options.manifestRolloverBytes()).isEqualTo(4L * 1024 * 1024);
    assertThat(options.env()).isSameAs(Env.DEFAULT);
    assertThat(Shale.DEFAULT_WRITE_BUFFER_SIZE_BYTES).isEqualTo(options.writeBufferSizeBytes());
  }

  @Test
  void withX_replacesOnlyThatField() {
    ShaleOptions options =
        ShaleOptions.defaults().withWriteBufferSizeBytes(64).withManifestRolloverBytes(1024);

    assertThat(options.writeBufferSizeBytes()).isEqualTo(64);
    assertThat(options.manifestRolloverBytes()).isEqualTo(1024);
    assertThat(options.env()).isSameAs(Env.DEFAULT);
  }

  @Test
  void nonPositiveSizes_andANullEnv_areRejected() {
    ShaleOptions options = ShaleOptions.defaults();

    assertThatThrownBy(() -> options.withWriteBufferSizeBytes(0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("writeBufferSizeBytes");
    assertThatThrownBy(() -> options.withManifestRolloverBytes(-1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("manifestRolloverBytes");
    assertThatThrownBy(() -> options.withEnv(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("env");
  }
}
