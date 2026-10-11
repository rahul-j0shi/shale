package dev.shale.sstable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.shale.BytewiseComparator;
import dev.shale.CorruptionException;
import dev.shale.KeyComparator;
import dev.shale.internal.coding.Crc32c;
import dev.shale.internal.coding.LittleEndian;
import dev.shale.internal.key.InternalKey;
import dev.shale.internal.key.InternalKeyComparator;
import dev.shale.internal.key.ValueType;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Round-trips a table through {@link SSTableWriter} and {@link SSTableReader}: point lookups by
 * ceiling, full ordered iteration, and detection of a footer whose magic has been corrupted. Values
 * are large enough that the writer produces several data blocks, exercising the index.
 */
class SSTableTest {

  private static final KeyComparator ORDERING =
      new InternalKeyComparator(BytewiseComparator.INSTANCE);
  private static final int KEYS = 300;

  @TempDir private Path dir;

  @Test
  void roundTrip_ceilingLookupsAndOrderedIteration() throws IOException {
    Path path = dir.resolve("000001.sst");
    List<byte[]> internalKeys = writeTable(path);

    SSTableReader reader = SSTableReader.open(path, ORDERING);
    try {
      // point lookups: ceiling of a seek key lands on that user key's stored version
      for (int i = 0; i < KEYS; i++) {
        SSTableReader.Entry entry = reader.ceiling(seek(userKey(i)));
        assertThat(entry).isNotNull();
        assertThat(InternalKey.decode(entry.internalKey()).userKey()).isEqualTo(userKey(i));
        assertThat(entry.value()).isEqualTo(value(i));
      }
      // a key past the end has no ceiling
      assertThat(reader.ceiling(seek(bytes("~~~beyond")))).isNull();

      // full iteration is in stored (internal-key) order (compared by content, not array identity)
      List<String> iterated = new ArrayList<>();
      for (SSTableReader.Entry entry : SSTables.entries(reader)) {
        iterated.add(HexFormat.of().formatHex(entry.internalKey()));
      }
      List<String> expected = internalKeys.stream().map(HexFormat.of()::formatHex).toList();
      assertThat(iterated).isEqualTo(expected);
    } finally {
      reader.release();
    }
  }

  @Test
  void flippingAnyBit_isDetectedOrHarmless() throws IOException {
    // A small table so every bit can be flipped (on-disk-formats.md §3 step 7). A flip is harmless
    // only if the decoded contents equal the original's; anything else must be CorruptionException.
    Path path = dir.resolve("small.sst");
    try (SSTableWriter writer = SSTableWriter.open(path)) {
      for (int i = 0; i < 5; i++) {
        writer.add(new InternalKey(userKey(i), i + 1, ValueType.PUT).encode(), value(i));
      }
      writer.finish();
    }
    byte[] original = Files.readAllBytes(path);
    List<String> expected = fullyRead(path);

    Path victim = dir.resolve("victim.sst");
    List<String> silent = new ArrayList<>();
    for (int bit = 0; bit < original.length * 8; bit++) {
      byte[] flipped = original.clone();
      flipped[bit / 8] ^= (byte) (1 << (bit % 8));
      Files.write(victim, flipped);
      try {
        if (!fullyRead(victim).equals(expected)) {
          silent.add("offset " + bit / 8 + " bit " + bit % 8);
        }
      } catch (CorruptionException detected) {
        // detected — the acceptable outcome (N4)
      }
    }

    assertThat(silent).as("flips that silently changed the data").isEmpty();
  }

  /** Opens the table, drains every entry, and probes a ceiling — reading all blocks (and CRCs). */
  private static List<String> fullyRead(Path path) throws IOException {
    SSTableReader reader = SSTableReader.open(path, ORDERING);
    try {
      List<String> rows = new ArrayList<>();
      for (SSTableReader.Entry entry : SSTables.entries(reader)) {
        rows.add(HexFormat.of().formatHex(entry.internalKey()));
      }
      SSTableReader.Entry ceiling = reader.ceiling(seek(userKey(0)));
      rows.add(ceiling == null ? "null" : HexFormat.of().formatHex(ceiling.value()));
      return rows;
    } finally {
      reader.release();
    }
  }

  @Test
  void corruptedFooterMagic_isCorruption() throws IOException {
    Path path = dir.resolve("000002.sst");
    writeTable(path);
    byte[] bytes = Files.readAllBytes(path);
    bytes[bytes.length - 1] ^= (byte) 0xFF; // last byte is the top byte of the magic
    Files.write(path, bytes);

    assertThatThrownBy(() -> SSTableReader.open(path, ORDERING))
        .isInstanceOf(CorruptionException.class);
  }

  @Test
  void sh15_footerAliasingTheMetaindexAsTheIndex_failsAtOpen() throws IOException {
    // SH-15: rewrite the footer so its index handle names the (valid, empty) metaindex block.
    // Both blocks' CRCs are intact; M4 accepted the alias and the table exposed no data at all.
    Path path = dir.resolve("000003.sst");
    writeTable(path);
    Footer footer = footerOf(path);
    rewriteFooter(path, new Footer(footer.metaindexHandle(), footer.metaindexHandle()));

    assertThatThrownBy(() -> SSTableReader.open(path, ORDERING))
        .isExactlyInstanceOf(CorruptionException.class)
        .hasMessageContaining("index block");
  }

  @Test
  void footerWhoseMetaindexIsTheIndex_failsAtOpen() throws IOException {
    Path path = dir.resolve("000004.sst");
    writeTable(path);
    Footer footer = footerOf(path);
    rewriteFooter(path, new Footer(footer.indexHandle(), footer.indexHandle()));

    assertThatThrownBy(() -> SSTableReader.open(path, ORDERING))
        .isExactlyInstanceOf(CorruptionException.class)
        .hasMessageContaining("metaindex");
  }

  @Test
  void dataHandlePointingIntoTheMetaindex_failsAtOpen() throws IOException {
    // A table built by hand: one data block, the empty metaindex, and an index whose only entry
    // points at the metaindex instead of the data block. Every block's CRC is valid.
    Path path = dir.resolve("000005.sst");
    byte[] key = new InternalKey(bytes("a"), 1, ValueType.PUT).encode();
    BlockBuilder data = new BlockBuilder(16);
    data.add(key, bytes("1"));
    byte[] dataBlock = data.finish();
    byte[] metaindex = new BlockBuilder(16).finish();
    BlockHandle metaHandle = new BlockHandle(frameSize(dataBlock), metaindex.length);
    BlockBuilder index = new BlockBuilder(16);
    index.add(key, encode(metaHandle)); // the wrong role
    byte[] indexBlock = index.finish();
    BlockHandle indexHandle =
        new BlockHandle(frameSize(dataBlock) + frameSize(metaindex), indexBlock.length);
    Files.write(
        path,
        concat(
            frame(dataBlock),
            frame(metaindex),
            frame(indexBlock),
            new Footer(metaHandle, indexHandle).encode()));

    assertThatThrownBy(() -> SSTableReader.open(path, ORDERING))
        .isExactlyInstanceOf(CorruptionException.class)
        .hasMessageContaining("data block");
  }

  @Test
  void sh16_indexSizeMinusOne_isCorruptionNotArrayIndexOutOfBounds() throws IOException {
    // SH-16: an index size of 2^64 - 1 (a long of -1) escaped as ArrayIndexOutOfBoundsException.
    Path path = dir.resolve("000006.sst");
    writeTable(path);
    Footer footer = footerOf(path);
    rewriteFooter(
        path,
        new Footer(
            footer.metaindexHandle(), new BlockHandle(footer.indexHandle().offsetBytes(), -1L)));

    assertThatThrownBy(() -> SSTableReader.open(path, ORDERING))
        .isExactlyInstanceOf(CorruptionException.class);
  }

  @Test
  void sh16_indexSizeTwoToThe32_isCorruptionNotArithmeticException() throws IOException {
    Path path = dir.resolve("000007.sst");
    writeTable(path);
    Footer footer = footerOf(path);
    rewriteFooter(
        path,
        new Footer(
            footer.metaindexHandle(),
            new BlockHandle(footer.indexHandle().offsetBytes(), 1L << 32)));

    assertThatThrownBy(() -> SSTableReader.open(path, ORDERING))
        .isExactlyInstanceOf(CorruptionException.class);
  }

  @Test
  void indexOffsetPastTheEndOfTheFile_isCorruption() throws IOException {
    Path path = dir.resolve("000008.sst");
    writeTable(path);
    Footer footer = footerOf(path);
    rewriteFooter(
        path, new Footer(footer.metaindexHandle(), new BlockHandle(Long.MAX_VALUE - 2, 10)));

    assertThatThrownBy(() -> SSTableReader.open(path, ORDERING))
        .isExactlyInstanceOf(CorruptionException.class);
  }

  @Test
  void emptyTable_stillOpens() throws IOException {
    Path path = dir.resolve("000009.sst");
    try (SSTableWriter writer = SSTableWriter.open(path)) {
      writer.finish();
    }

    SSTableReader reader = SSTableReader.open(path, ORDERING);
    try {
      assertThat(SSTables.entries(reader)).isEmpty();
    } finally {
      reader.release();
    }
  }

  private static Footer footerOf(Path path) throws IOException {
    byte[] bytes = Files.readAllBytes(path);
    int start = bytes.length - SSTableFormat.FOOTER_SIZE;
    return Footer.decode(Arrays.copyOfRange(bytes, start, bytes.length), start);
  }

  private static void rewriteFooter(Path path, Footer footer) throws IOException {
    byte[] bytes = Files.readAllBytes(path);
    byte[] encoded = footer.encode();
    System.arraycopy(encoded, 0, bytes, bytes.length - encoded.length, encoded.length);
    Files.write(path, bytes);
  }

  /** A block's content followed by its trailer: type 0 (none), then CRC32C of content ‖ type. */
  private static byte[] frame(byte[] content) {
    byte[] framed = Arrays.copyOf(content, content.length + SSTableFormat.BLOCK_TRAILER_SIZE);
    framed[content.length] = SSTableFormat.BLOCK_TYPE_NONE;
    LittleEndian.putFixed32(framed, content.length + 1, Crc32c.of(framed, 0, content.length + 1));
    return framed;
  }

  private static long frameSize(byte[] content) {
    return content.length + SSTableFormat.BLOCK_TRAILER_SIZE;
  }

  private static byte[] encode(BlockHandle handle) {
    byte[] out = new byte[handle.encodedLength()];
    handle.encodeTo(out, 0);
    return out;
  }

  private static byte[] concat(byte[]... parts) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (byte[] part : parts) {
      out.writeBytes(part);
    }
    return out.toByteArray();
  }

  /**
   * Writes {@link #KEYS} put entries in internal-key order; returns their encoded internal keys.
   */
  private List<byte[]> writeTable(Path path) throws IOException {
    List<byte[]> internalKeys = new ArrayList<>();
    try (SSTableWriter writer = SSTableWriter.open(path)) {
      for (int i = 0; i < KEYS; i++) {
        byte[] internalKey = new InternalKey(userKey(i), i + 1, ValueType.PUT).encode();
        internalKeys.add(internalKey);
        writer.add(internalKey, value(i));
      }
      writer.finish();
    }
    return internalKeys;
  }

  private static byte[] userKey(int i) {
    return String.format("key%05d", i).getBytes(StandardCharsets.US_ASCII);
  }

  private static byte[] seek(byte[] userKey) {
    return new InternalKey(userKey, InternalKey.MAX_SEQUENCE, ValueType.FOR_SEEK).encode();
  }

  private static byte[] value(int i) {
    byte[] value = new byte[64]; // large enough that the table spans several data blocks
    for (int j = 0; j < value.length; j++) {
      value[j] = (byte) (i + j);
    }
    return value;
  }

  private static byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.US_ASCII);
  }
}
