package dev.shale.sstable;

import static org.assertj.core.api.Assertions.assertThat;

import dev.shale.BytewiseComparator;
import dev.shale.KeyComparator;
import dev.shale.internal.key.InternalKey;
import dev.shale.internal.key.InternalKeyComparator;
import dev.shale.internal.key.ValueType;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;
import net.jqwik.api.lifecycle.AfterTry;
import net.jqwik.api.lifecycle.BeforeTry;

/**
 * Round-trip fidelity (on-disk-formats.md §3): an arbitrary set of entries, written to an SSTable
 * and read back, must iterate identically and answer every ceiling lookup the same as an in-memory
 * ordered map. Unique per-op sequence numbers keep the internal keys distinct, as the engine does.
 */
class SSTablePropertyTest {

  private static final KeyComparator ORDERING =
      new InternalKeyComparator(BytewiseComparator.INSTANCE);

  private Path dir;

  @BeforeTry
  void makeDir() throws IOException {
    dir = Files.createTempDirectory("sstable-property");
  }

  @AfterTry
  void cleanDir() throws IOException {
    try (var paths = Files.walk(dir)) {
      paths.sorted((a, b) -> b.compareTo(a)).forEach(SSTablePropertyTest::deleteQuietly);
    }
  }

  @Property(tries = 200)
  void writeThenRead_matchesAnOrderedMap(
      @ForAll @Size(min = 1, max = 300) List<@IntRange(min = 0, max = 63) Integer> userKeyIds)
      throws IOException {
    // Build the expected sorted state: unique internal keys (seq = index), newest-per-key ordering.
    TreeMap<byte[], byte[]> expected = new TreeMap<>(ORDERING::compare);
    long sequence = 1;
    for (int id : userKeyIds) {
      byte[] internalKey = new InternalKey(userKey(id), sequence, ValueType.PUT).encode();
      expected.put(internalKey, value(id, sequence));
      sequence++;
    }

    Path path = dir.resolve("table.sst");
    try (SSTableWriter writer = SSTableWriter.open(path)) {
      for (Map.Entry<byte[], byte[]> entry : expected.entrySet()) {
        writer.add(entry.getKey(), entry.getValue());
      }
      writer.finish();
    }

    SSTableReader reader = SSTableReader.open(path, ORDERING);
    try {
      List<String> iterated =
          reader.entries().stream().map(e -> hex(e.internalKey()) + "=" + hex(e.value())).toList();
      List<String> expectedRows =
          expected.entrySet().stream().map(e -> hex(e.getKey()) + "=" + hex(e.getValue())).toList();
      assertThat(iterated).isEqualTo(expectedRows);

      // ceiling of a seek key returns the same entry the ordered map's ceilingEntry does
      for (int id = 0; id <= 63; id++) {
        byte[] seek =
            new InternalKey(userKey(id), InternalKey.MAX_SEQUENCE, ValueType.FOR_SEEK).encode();
        Map.Entry<byte[], byte[]> expectedEntry = expected.ceilingEntry(seek);
        SSTableReader.Entry actual = reader.ceiling(seek);
        if (expectedEntry == null) {
          assertThat(actual).isNull();
        } else {
          assertThat(hex(actual.internalKey())).isEqualTo(hex(expectedEntry.getKey()));
          assertThat(hex(actual.value())).isEqualTo(hex(expectedEntry.getValue()));
        }
      }
    } finally {
      reader.release();
    }
  }

  private static byte[] userKey(int id) {
    return new byte[] {(byte) id};
  }

  private static byte[] value(int id, long sequence) {
    return new byte[] {(byte) id, (byte) sequence, (byte) (sequence >>> 8)};
  }

  private static String hex(byte[] bytes) {
    return HexFormat.of().formatHex(bytes);
  }

  private static void deleteQuietly(Path path) {
    try {
      Files.deleteIfExists(path);
    } catch (IOException ignored) {
      // best effort in test teardown
    }
  }
}
