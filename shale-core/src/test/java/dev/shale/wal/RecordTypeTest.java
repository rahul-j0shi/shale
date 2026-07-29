package dev.shale.wal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.shale.CorruptionException;
import org.junit.jupiter.api.Test;

class RecordTypeTest {

  @Test
  void codes_matchLevelDb() {
    assertThat(RecordType.ZERO.code()).isZero();
    assertThat(RecordType.FULL.code()).isEqualTo(1);
    assertThat(RecordType.FIRST.code()).isEqualTo(2);
    assertThat(RecordType.MIDDLE.code()).isEqualTo(3);
    assertThat(RecordType.LAST.code()).isEqualTo(4);
  }

  @Test
  void fromCode_roundTripsKnownCodes() {
    assertThat(RecordType.fromCode(1)).isEqualTo(RecordType.FULL);
    assertThat(RecordType.fromCode(4)).isEqualTo(RecordType.LAST);
  }

  @Test
  void fromCode_rejectsUnknown() {
    assertThatThrownBy(() -> RecordType.fromCode(9)).isInstanceOf(CorruptionException.class);
  }
}
