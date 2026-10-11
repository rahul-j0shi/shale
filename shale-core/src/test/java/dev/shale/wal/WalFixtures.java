package dev.shale.wal;

import dev.shale.internal.key.InternalKey;
import dev.shale.internal.key.ValueType;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * The logical contents of the golden WAL fixtures, in one place. {@link WalFixtureGenerator} wrote
 * the frozen files from these definitions once; {@code GoldenWalTest} decodes the frozen files and
 * compares them with these definitions. Changing a definition here therefore fails the golden test
 * rather than silently matching a regenerated file (on-disk-formats.md §4).
 */
final class WalFixtures {

  /** One logical record: a PUT of {@code value} under {@code userKey} at {@code sequence}. */
  record Mutation(String userKey, long sequence, byte[] value) {

    InternalKey internalKey() {
      return new InternalKey(ascii(userKey), sequence, ValueType.PUT);
    }

    byte[] payload() {
      return WalRecordCodec.encode(internalKey(), value);
    }
  }

  /** {@code key}→{@code v} at sequence 1: the worked example of {@code wal/format.md} §5. */
  static final List<Mutation> SINGLE_PUT = List.of(new Mutation("key", 1, ascii("v")));

  /**
   * The engine-level fixture of the October 2026 review: {@code A}→{@code ack-A} and {@code
   * B}→{@code ack-B}, as {@code Shale.put(..., SYNC)} writes them into a fresh segment. In v1 it is
   * 62 bytes, with the first fragment header at offset 16 (CRC 16–19, length 20–21, type 22).
   */
  static final List<Mutation> TWO_PUTS =
      List.of(new Mutation("A", 1, ascii("ack-A")), new Mutation("B", 2, ascii("ack-B")));

  /** Bytes in {@link #FRAGMENTED}'s large value: more than two 32 KiB blocks. */
  static final int BIG_VALUE_SIZE = 80_000;

  /**
   * One record spanning three blocks ({@code FIRST}, {@code MIDDLE}, {@code LAST}), then a small
   * one, so reassembly and the block after a fragmented record are both covered.
   */
  static final List<Mutation> FRAGMENTED =
      List.of(
          new Mutation("big", 1, patterned(BIG_VALUE_SIZE)), new Mutation("small", 2, ascii("v")));

  /** Padding bytes the v1 writer leaves at the end of blocks 0 and 1 of {@link #BLOCK_PADDING}. */
  static final int V1_PADDING_BLOCK_0 = 3;

  static final int V1_PADDING_BLOCK_1 = 6;

  /**
   * Three records sized for the v1 framing (7-byte fragment headers) so that block 0 ends with 3
   * zero padding bytes and block 1 with 6, then a small record in block 2. A payload here is {@code
   * varint(10) ‖ 10-byte internal key ‖ varint(value length, 3 bytes) ‖ value}, i.e. value length +
   * 14; a fragment is the payload + 7. Block 0: 32768 − 3 − 7 − 14 = 32744 value bytes; block 1:
   * 32768 − 6 − 7 − 14 = 32741.
   */
  static final List<Mutation> BLOCK_PADDING =
      List.of(
          new Mutation("p1", 1, patterned(32_768 - V1_PADDING_BLOCK_0 - 7 - 14)),
          new Mutation("p2", 2, patterned(32_768 - V1_PADDING_BLOCK_1 - 7 - 14)),
          new Mutation("p3", 3, ascii("tail")));

  /**
   * Padding bytes the v2 writer leaves at the end of blocks 0 and 1 of {@link #BLOCK_PADDING_V2}.
   */
  static final int V2_PADDING_BLOCK_0 = 3;

  static final int V2_PADDING_BLOCK_1 = 10;

  /**
   * {@link #BLOCK_PADDING} resized for v2's 11-byte fragment headers: block 0 ends with 3 padding
   * bytes and block 1 with 10, one less than a header. Block 0: 32768 − 3 − 11 − 14 = 32740 value
   * bytes; block 1: 32768 − 10 − 11 − 14 = 32733.
   */
  static final List<Mutation> BLOCK_PADDING_V2 =
      List.of(
          new Mutation("p1", 1, patterned(32_768 - V2_PADDING_BLOCK_0 - 11 - 14)),
          new Mutation("p2", 2, patterned(32_768 - V2_PADDING_BLOCK_1 - 11 - 14)),
          new Mutation("p3", 3, ascii("tail")));

  private WalFixtures() {}

  /** The block-padding fixture for a framing {@code version}. */
  static List<Mutation> blockPadding(int version) {
    return version == 1 ? BLOCK_PADDING : BLOCK_PADDING_V2;
  }

  /** Deterministic, non-repeating-looking bytes, so a misplaced block cannot decode by accident. */
  static byte[] patterned(int size) {
    byte[] bytes = new byte[size];
    for (int i = 0; i < size; i++) {
      bytes[i] = (byte) ((i * 31 + 7) % 251);
    }
    return bytes;
  }

  static byte[] ascii(String text) {
    return text.getBytes(StandardCharsets.US_ASCII);
  }
}
