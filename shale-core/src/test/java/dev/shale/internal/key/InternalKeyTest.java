package dev.shale.internal.key;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.shale.CorruptionException;
import org.junit.jupiter.api.Test;

class InternalKeyTest {

  @Test
  void encode_appendsLittleEndianTrailer() {
    // user key "key" = 0x6B6579, seq 5, PUT -> trailer (5<<8)|1 = 0x0501
    InternalKey ik = new InternalKey(new byte[] {0x6B, 0x65, 0x79}, 5, ValueType.PUT);

    assertThat(ik.encode())
        .containsExactly(0x6B, 0x65, 0x79, 0x01, 0x05, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00);
  }

  @Test
  void decode_recoversFields() {
    byte[] encoded = new InternalKey(new byte[] {1, 2, 3}, 42, ValueType.DELETE).encode();

    InternalKey back = InternalKey.decode(encoded);

    assertThat(back.userKey()).containsExactly(1, 2, 3);
    assertThat(back.sequenceNumber()).isEqualTo(42);
    assertThat(back.valueType()).isEqualTo(ValueType.DELETE);
  }

  @Test
  void construct_rejectsSequenceAboveFiftySixBits() {
    assertThatThrownBy(
            () -> new InternalKey(new byte[] {1}, InternalKey.MAX_SEQUENCE + 1, ValueType.PUT))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void decode_rejectsTooShortInput() {
    assertThatThrownBy(() -> InternalKey.decode(new byte[] {0, 0, 0}))
        .isInstanceOf(CorruptionException.class);
  }

  @Test
  void equals_comparesByValueNotArrayIdentity() {
    assertThat(new InternalKey(new byte[] {9}, 1, ValueType.PUT))
        .isEqualTo(new InternalKey(new byte[] {9}, 1, ValueType.PUT));
  }
}
