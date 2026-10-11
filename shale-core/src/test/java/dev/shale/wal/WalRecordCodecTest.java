package dev.shale.wal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.shale.CorruptionException;
import dev.shale.internal.coding.Varints;
import dev.shale.internal.key.InternalKey;
import dev.shale.internal.key.ValueType;
import java.io.ByteArrayOutputStream;
import org.junit.jupiter.api.Test;

class WalRecordCodecTest {

  /** A valid 9-byte internal key: user key "X", sequence 1, PUT. */
  private static final byte[] NINE_BYTE_KEY =
      new InternalKey(new byte[] {'X'}, 1, ValueType.PUT).encode();

  @Test
  void encodeDecode_putRoundTrips() {
    InternalKey key = new InternalKey(new byte[] {0x6B, 0x65, 0x79}, 7, ValueType.PUT);

    WalRecordCodec.Decoded back = WalRecordCodec.decode(WalRecordCodec.encode(key, new byte[] {9}));

    assertThat(back.internalKey()).isEqualTo(key);
    assertThat(back.value()).containsExactly(9);
  }

  @Test
  void encodeDecode_deleteHasEmptyValue() {
    InternalKey key = new InternalKey(new byte[] {1, 2}, 42, ValueType.DELETE);

    WalRecordCodec.Decoded back = WalRecordCodec.decode(WalRecordCodec.encode(key, new byte[0]));

    assertThat(back.internalKey()).isEqualTo(key);
    assertThat(back.value()).isEmpty();
  }

  @Test
  void decode_keyLengthTwoToThe32PlusNine_isCorruptionNotNine() {
    // SH-13: a representable 64-bit length narrowed with (int) became 9, matching the 9 key bytes
    // actually present, and the record decoded as if nothing were wrong.
    byte[] payload = payload((1L << 32) + 9, NINE_BYTE_KEY, 0, new byte[0]);

    assertThatThrownBy(() -> WalRecordCodec.decode(payload))
        .isInstanceOf(CorruptionException.class);
  }

  @Test
  void decode_keyLengthTwoToThe31_isOverrunBeforeAllocation() {
    byte[] payload = payload(1L << 31, NINE_BYTE_KEY, 0, new byte[0]);

    assertThatThrownBy(() -> WalRecordCodec.decode(payload))
        .isInstanceOf(CorruptionException.class)
        .hasMessageContaining("overruns");
  }

  @Test
  void decode_keyLengthTwoToThe32MinusOne_isOverrun() {
    byte[] payload = payload(0xFFFF_FFFFL, NINE_BYTE_KEY, 0, new byte[0]);

    assertThatThrownBy(() -> WalRecordCodec.decode(payload))
        .isInstanceOf(CorruptionException.class)
        .hasMessageContaining("overruns");
  }

  @Test
  void decode_valueLengthOverrun_isCorruption() {
    byte[] payload = payload(NINE_BYTE_KEY.length, NINE_BYTE_KEY, 5, new byte[] {1, 2});

    assertThatThrownBy(() -> WalRecordCodec.decode(payload))
        .isInstanceOf(CorruptionException.class)
        .hasMessageContaining("overruns");
  }

  @Test
  void decode_trailingByteAfterValue_isCorruption() {
    byte[] payload = payload(NINE_BYTE_KEY.length, NINE_BYTE_KEY, 1, new byte[] {7, 99});

    assertThatThrownBy(() -> WalRecordCodec.decode(payload))
        .isInstanceOf(CorruptionException.class)
        .hasMessageContaining("trailing");
  }

  @Test
  void decode_valueExactlyFillingThePayload_decodes() {
    byte[] payload = payload(NINE_BYTE_KEY.length, NINE_BYTE_KEY, 2, new byte[] {7, 8});

    WalRecordCodec.Decoded decoded = WalRecordCodec.decode(payload);

    assertThat(decoded.internalKey().userKey()).containsExactly('X');
    assertThat(decoded.value()).containsExactly(7, 8);
  }

  /** {@code varint(keyLength) ‖ key ‖ varint(valueLength) ‖ value}, lengths as declared. */
  private static byte[] payload(long keyLength, byte[] key, long valueLength, byte[] value) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.writeBytes(varint(keyLength));
    out.writeBytes(key);
    out.writeBytes(varint(valueLength));
    out.writeBytes(value);
    return out.toByteArray();
  }

  private static byte[] varint(long value) {
    byte[] buf = new byte[Varints.size(value)];
    Varints.put(buf, 0, value);
    return buf;
  }
}
