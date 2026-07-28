package dev.shale.internal.coding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.shale.CorruptionException;
import org.junit.jupiter.api.Test;

class VarintsTest {

  @Test
  void put_encodesLevelDbExample() {
    // 300 = 0b100101100 -> 0xAC 0x02 (LevelDB varint32)
    byte[] buf = new byte[2];
    int next = Varints.put(buf, 0, 300);

    assertThat(next).isEqualTo(2);
    assertThat(buf).containsExactly(0xAC, 0x02);
  }

  @Test
  void get_decodesAtOffset() {
    byte[] buf = new byte[5];
    buf[0] = 0x2A; // filler
    int end = Varints.put(buf, 1, 300);

    Varints.Decoded decoded = Varints.get(buf, 1);

    assertThat(decoded.value()).isEqualTo(300);
    assertThat(decoded.nextOffset()).isEqualTo(end);
  }

  @Test
  void get_rejectsTruncatedVarint() {
    // high bit set on the only byte => more bytes expected, but the array ends
    assertThatThrownBy(() -> Varints.get(new byte[] {(byte) 0x80}, 0))
        .isInstanceOf(CorruptionException.class);
  }

  @Test
  void get_rejectsOverlongVarint() {
    byte[] eleven = new byte[11];
    for (int i = 0; i < 11; i++) {
      eleven[i] = (byte) 0x80; // never terminates within 10 bytes
    }
    assertThatThrownBy(() -> Varints.get(eleven, 0)).isInstanceOf(CorruptionException.class);
  }

  @Test
  void size_matchesBytesWritten() {
    assertThat(Varints.size(0)).isEqualTo(1);
    assertThat(Varints.size(127)).isEqualTo(1);
    assertThat(Varints.size(128)).isEqualTo(2);
    assertThat(Varints.size(300)).isEqualTo(2);
  }
}
