package dev.shale.internal.key;

import static org.assertj.core.api.Assertions.assertThat;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.LongRange;
import net.jqwik.api.constraints.Size;

class InternalKeyPropertyTest {

  @Property
  void encodeDecode_roundTrips(
      @ForAll @Size(max = 64) byte[] userKey,
      @ForAll @LongRange(min = 0, max = (1L << 56) - 1) long seq) {
    InternalKey ik = new InternalKey(userKey, seq, ValueType.PUT);

    InternalKey back = InternalKey.decode(ik.encode());

    assertThat(back.userKey()).containsExactly(userKey);
    assertThat(back.sequenceNumber()).isEqualTo(seq);
    assertThat(back.valueType()).isEqualTo(ValueType.PUT);
  }
}
