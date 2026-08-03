package dev.shale.sstable;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BlockHandleTest {

  @Test
  void encodeDecode_roundTrips() {
    BlockHandle handle = new BlockHandle(300, 42);

    byte[] buffer = new byte[handle.encodedLength()];
    int next = handle.encodeTo(buffer, 0);
    BlockHandle.Decoded decoded = BlockHandle.decode(buffer, 0);

    assertThat(next).isEqualTo(buffer.length);
    assertThat(decoded.handle()).isEqualTo(handle);
    assertThat(decoded.nextOffset()).isEqualTo(buffer.length);
  }

  @Test
  void encodesAtAnOffset_intoASharedBuffer() {
    BlockHandle first = new BlockHandle(0, 17);
    BlockHandle second = new BlockHandle(22, 5);

    byte[] buffer = new byte[first.encodedLength() + second.encodedLength()];
    int afterFirst = first.encodeTo(buffer, 0);
    int afterSecond = second.encodeTo(buffer, afterFirst);

    assertThat(afterSecond).isEqualTo(buffer.length);
    assertThat(BlockHandle.decode(buffer, 0).handle()).isEqualTo(first);
    assertThat(BlockHandle.decode(buffer, afterFirst).handle()).isEqualTo(second);
  }
}
