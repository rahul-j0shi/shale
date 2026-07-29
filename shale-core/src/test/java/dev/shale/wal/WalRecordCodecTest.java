package dev.shale.wal;

import static org.assertj.core.api.Assertions.assertThat;

import dev.shale.internal.key.InternalKey;
import dev.shale.internal.key.ValueType;
import org.junit.jupiter.api.Test;

class WalRecordCodecTest {

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
}
