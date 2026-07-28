package dev.shale.internal.coding;

import static org.assertj.core.api.Assertions.assertThat;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.LongRange;

class VarintsPropertyTest {

  @Property
  void putThenGet_roundTrips(@ForAll @LongRange(min = 0, max = Long.MAX_VALUE) long value) {
    byte[] buf = new byte[Varints.size(value)];

    int next = Varints.put(buf, 0, value);
    Varints.Decoded decoded = Varints.get(buf, 0);

    assertThat(next).isEqualTo(buf.length);
    assertThat(decoded.value()).isEqualTo(value);
    assertThat(decoded.nextOffset()).isEqualTo(buf.length);
  }
}
