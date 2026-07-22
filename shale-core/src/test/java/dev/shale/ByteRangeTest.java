package dev.shale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ByteRangeTest {

  @Test
  void of_spansWholeArray() {
    byte[] array = {1, 2, 3};

    ByteRange range = ByteRange.of(array);

    assertThat(range.offset()).isZero();
    assertThat(range.length()).isEqualTo(3);
    assertThat(range.array()).isSameAs(array);
  }

  @Test
  void construct_rejectsOutOfBoundsRange() {
    assertThatThrownBy(() -> new ByteRange(new byte[] {1, 2}, 1, 5))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
