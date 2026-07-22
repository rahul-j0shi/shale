package dev.shale.internal.key;

import static org.assertj.core.api.Assertions.assertThat;

import dev.shale.BytewiseComparator;
import org.junit.jupiter.api.Test;

class InternalKeyComparatorTest {

  private final InternalKeyComparator cmp = new InternalKeyComparator(BytewiseComparator.INSTANCE);

  @Test
  void differentUserKeys_orderByUserKeyAscending() {
    byte[] a = new InternalKey(new byte[] {1}, 100, ValueType.PUT).encode();
    byte[] b = new InternalKey(new byte[] {2}, 1, ValueType.PUT).encode();

    assertThat(cmp.compare(a, b)).isNegative();
  }

  @Test
  void sameUserKey_higherSequenceSortsFirst() {
    byte[] newer = new InternalKey(new byte[] {1}, 100, ValueType.PUT).encode();
    byte[] older = new InternalKey(new byte[] {1}, 50, ValueType.PUT).encode();

    assertThat(cmp.compare(newer, older)).isNegative(); // newer sorts before older
  }

  @Test
  void name_wrapsUserComparatorName() {
    assertThat(cmp.name()).contains("shale.BytewiseComparator");
  }
}
