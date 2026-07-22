package dev.shale;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BytewiseComparatorTest {

  private final KeyComparator cmp = BytewiseComparator.INSTANCE;

  @Test
  void compares_unsigned() {
    // 0x80 (128) must sort AFTER 0x7F (127), not before (signed byte trap)
    assertThat(cmp.compare(new byte[] {(byte) 0x80}, new byte[] {0x7F})).isPositive();
  }

  @Test
  void shorterPrefix_sortsFirst() {
    assertThat(cmp.compare(new byte[] {1, 2}, new byte[] {1, 2, 3})).isNegative();
  }

  @Test
  void equalArrays_compareEqual() {
    assertThat(cmp.compare(new byte[] {1, 2, 3}, new byte[] {1, 2, 3})).isZero();
  }

  @Test
  void name_isStable() {
    assertThat(cmp.name()).isEqualTo("shale.BytewiseComparator");
  }
}
