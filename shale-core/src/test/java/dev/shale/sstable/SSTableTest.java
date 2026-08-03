package dev.shale.sstable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.shale.BytewiseComparator;
import dev.shale.CorruptionException;
import dev.shale.KeyComparator;
import dev.shale.internal.key.InternalKey;
import dev.shale.internal.key.InternalKeyComparator;
import dev.shale.internal.key.ValueType;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
      for (SSTableReader.Entry entry : reader.entries()) {
        iterated.add(HexFormat.of().formatHex(entry.internalKey()));
      }
      List<String> expected = internalKeys.stream().map(HexFormat.of()::formatHex).toList();
      assertThat(iterated).isEqualTo(expected);
    } finally {
      reader.release();
    }
  }

  @Test
  void flippingAnyByte_isDetectedOrHarmless() throws IOException {
    // A small table so bit-flip-at-every-offset is cheap (on-disk-formats.md §3).
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
    for (int offset = 0; offset < original.length; offset++) {
      byte[] flipped = original.clone();
      flipped[offset] ^= (byte) 0xFF; // corrupt one byte
      Files.write(victim, flipped);
      try {
        assertThat(fullyRead(victim))
            .as("flip at offset %d must not silently change the data", offset)
            .isEqualTo(expected); // no exception → must be byte-identical (harmless)
      } catch (CorruptionException detected) {
        // detected — the acceptable outcome (N4)
      }
    }
  }

  /** Opens the table, drains every entry, and probes a ceiling — reading all blocks (and CRCs). */
  private static List<String> fullyRead(Path path) throws IOException {
    SSTableReader reader = SSTableReader.open(path, ORDERING);
    try {
      List<String> rows = new ArrayList<>();
      for (SSTableReader.Entry entry : reader.entries()) {
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
