package dev.shale.internal.key;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.shale.CorruptionException;
import org.junit.jupiter.api.Test;

class ValueTypeTest {

  @Test
  void codes_matchLevelDb() {
    assertThat(ValueType.DELETE.code()).isEqualTo((byte) 0x00);
    assertThat(ValueType.PUT.code()).isEqualTo((byte) 0x01);
  }

  @Test
  void forSeek_isPut() {
    assertThat(ValueType.FOR_SEEK).isEqualTo(ValueType.PUT);
  }

  @Test
  void fromCode_roundTripsKnownCodes() {
    assertThat(ValueType.fromCode((byte) 0x00)).isEqualTo(ValueType.DELETE);
    assertThat(ValueType.fromCode((byte) 0x01)).isEqualTo(ValueType.PUT);
  }

  @Test
  void fromCode_rejectsReservedCode() {
    assertThatThrownBy(() -> ValueType.fromCode((byte) 0x02))
        .isInstanceOf(CorruptionException.class);
  }
}
