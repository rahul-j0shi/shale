package dev.shale;

import static org.assertj.core.api.Assertions.assertThat;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;

class BytewiseComparatorPropertyTest {

  private final KeyComparator cmp = BytewiseComparator.INSTANCE;

  @Property
  void antisymmetric(@ForAll byte[] a, @ForAll byte[] b) {
    assertThat(Integer.signum(cmp.compare(a, b))).isEqualTo(-Integer.signum(cmp.compare(b, a)));
  }

  @Property
  void transitive(@ForAll byte[] a, @ForAll byte[] b, @ForAll byte[] c) {
    if (cmp.compare(a, b) <= 0 && cmp.compare(b, c) <= 0) {
      assertThat(cmp.compare(a, c)).isLessThanOrEqualTo(0);
    }
  }

  @Property
  void consistentWithEquality(@ForAll byte[] a) {
    assertThat(cmp.compare(a, a.clone())).isZero();
  }
}
