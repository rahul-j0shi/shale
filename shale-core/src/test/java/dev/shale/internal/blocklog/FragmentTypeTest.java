package dev.shale.internal.blocklog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.shale.CorruptionException;
import org.junit.jupiter.api.Test;

class FragmentTypeTest {

  @Test
  void codes_matchTheFormatDocument() {
    assertThat(FragmentType.ZERO.code()).isZero();
    assertThat(FragmentType.FULL.code()).isEqualTo(1);
    assertThat(FragmentType.FIRST.code()).isEqualTo(2);
    assertThat(FragmentType.MIDDLE.code()).isEqualTo(3);
    assertThat(FragmentType.LAST.code()).isEqualTo(4);
  }

  @Test
  void fromCode_decodesEveryKnownType() {
    assertThat(FragmentType.fromCode(1)).isEqualTo(FragmentType.FULL);
    assertThat(FragmentType.fromCode(4)).isEqualTo(FragmentType.LAST);
  }

  @Test
  void fromCode_rejectsAnUnknownType() {
    assertThatThrownBy(() -> FragmentType.fromCode(9)).isInstanceOf(CorruptionException.class);
  }
}
