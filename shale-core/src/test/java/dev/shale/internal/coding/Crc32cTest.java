package dev.shale.internal.coding;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class Crc32cTest {

  @Test
  void of_matchesKnownVector() {
    // CRC32C("123456789") = 0xE3069283 (standard Castagnoli check value)
    byte[] data = "123456789".getBytes(StandardCharsets.US_ASCII);

    assertThat(Crc32c.of(data)).isEqualTo(0xE3069283);
  }

  @Test
  void of_respectsRange() {
    byte[] framed = new byte[3 + 9];
    byte[] data = "123456789".getBytes(StandardCharsets.US_ASCII);
    System.arraycopy(data, 0, framed, 3, data.length);

    assertThat(Crc32c.of(framed, 3, 9)).isEqualTo(0xE3069283);
  }
}
