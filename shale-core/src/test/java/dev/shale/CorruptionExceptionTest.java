package dev.shale;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CorruptionExceptionTest {

  @Test
  void message_carriesOffsetAndValues() {
    CorruptionException e = new CorruptionException("unknown value type", 12, 1, 255);

    assertThat(e.getMessage()).contains("unknown value type");
    assertThat(e.offsetBytes()).isEqualTo(12);
    assertThat(e.expectedValue()).isEqualTo(1);
    assertThat(e.actualValue()).isEqualTo(255);
    assertThat(e).isInstanceOf(ShaleException.class);
  }
}
