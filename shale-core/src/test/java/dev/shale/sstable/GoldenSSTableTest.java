package dev.shale.sstable;

import static org.assertj.core.api.Assertions.assertThat;

import dev.shale.BytewiseComparator;
import dev.shale.KeyComparator;
import dev.shale.internal.key.InternalKey;
import dev.shale.internal.key.InternalKeyComparator;
import dev.shale.internal.key.ValueType;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The current reader must still decode the frozen v1 fixture (on-disk-formats.md §4). This is the
 * one mechanism that catches an accidental format change: {@code two-puts.sst} was written once by
 * the M3 writer and is never regenerated — if this fails, the reader changed behaviour.
 */
class GoldenSSTableTest {

  private static final KeyComparator ORDERING =
      new InternalKeyComparator(BytewiseComparator.INSTANCE);

  @TempDir private Path dir;

  @Test
  void decodesFrozenTwoPutsFixture() throws IOException {
    Path path = dir.resolve("two-puts.sst");
    try (InputStream in = getClass().getResourceAsStream("/golden/sstable/v1/two-puts.sst")) {
      assertThat(in).as("golden fixture on the classpath").isNotNull();
      Files.copy(in, path);
    }

    SSTableReader reader = SSTableReader.open(path, ORDERING);
    try {
      List<SSTableReader.Entry> entries = reader.entries();
      assertThat(entries).hasSize(2);
      assertEntry(entries.get(0), "a", 1, "1");
      assertEntry(entries.get(1), "b", 2, "22");

      assertEntry(reader.ceiling(seek("a")), "a", 1, "1");
      assertEntry(reader.ceiling(seek("b")), "b", 2, "22");
      assertThat(reader.ceiling(seek("c"))).isNull();
    } finally {
      reader.release();
    }
  }

  private static void assertEntry(
      SSTableReader.Entry entry, String userKey, long sequence, String value) {
    InternalKey key = InternalKey.decode(entry.internalKey());
    assertThat(key.userKey()).isEqualTo(ascii(userKey));
    assertThat(key.sequenceNumber()).isEqualTo(sequence);
    assertThat(key.valueType()).isEqualTo(ValueType.PUT);
    assertThat(entry.value()).isEqualTo(ascii(value));
  }

  private static byte[] seek(String userKey) {
    return new InternalKey(ascii(userKey), InternalKey.MAX_SEQUENCE, ValueType.FOR_SEEK).encode();
  }

  private static byte[] ascii(String value) {
    return value.getBytes(StandardCharsets.US_ASCII);
  }
}
