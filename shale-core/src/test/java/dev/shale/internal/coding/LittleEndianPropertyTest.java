package dev.shale.internal.coding;

import static org.assertj.core.api.Assertions.assertThat;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import org.junit.jupiter.api.Test;

class LittleEndianPropertyTest {

  @Property
  void putThenGet_roundTrips(@ForAll long value) {
    byte[] buf = new byte[8];
    LittleEndian.putFixed64(buf, 0, value);

    assertThat(LittleEndian.getFixed64(buf, 0)).isEqualTo(value);
  }

  @Test
  void putFixed64_writesLeastSignificantByteFirst() {
    byte[] buf = new byte[8];
    LittleEndian.putFixed64(buf, 0, 0x0102030405060708L);

    assertThat(buf).containsExactly(0x08, 0x07, 0x06, 0x05, 0x04, 0x03, 0x02, 0x01);
  }

  @Property
  void respectsOffset(@ForAll long value) {
    byte[] buf = new byte[16];
    LittleEndian.putFixed64(buf, 5, value);

    assertThat(LittleEndian.getFixed64(buf, 5)).isEqualTo(value);
  }
}
