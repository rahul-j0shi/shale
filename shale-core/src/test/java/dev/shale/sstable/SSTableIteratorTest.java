package dev.shale.sstable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.shale.BytewiseComparator;
import dev.shale.CorruptionException;
import dev.shale.KeyComparator;
import dev.shale.internal.key.InternalKey;
import dev.shale.internal.key.InternalKeyComparator;
import dev.shale.internal.key.ValueType;
import dev.shale.iterator.InternalIterator;
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
 * The two-level iterator: index block → data block, opening data blocks lazily as it advances
 * (ADR-0011). Tables here are deliberately large enough to span many data blocks, because a
 * single-block table would pass every one of these assertions while the block-to-block transition —
 * the only part that is actually new — went untested.
 */
class SSTableIteratorTest {

  private static final KeyComparator ORDERING =
      new InternalKeyComparator(BytewiseComparator.INSTANCE);
  private static final int KEYS = 300;

  @TempDir private Path dir;

  @Test
  void iteration_isOrdered_andSpansEveryDataBlock() throws IOException {
    Path path = dir.resolve("000001.sst");
    List<byte[]> written = writeTable(path, KEYS);

    SSTableReader reader = SSTableReader.open(path, ORDERING);
    try (InternalIterator iterator = reader.iterator()) {
      List<String> read = new ArrayList<>();
      for (iterator.seekToFirst(); iterator.valid(); iterator.next()) {
        read.add(hex(iterator.internalKey()));
      }
      assertThat(read).isEqualTo(written.stream().map(SSTableIteratorTest::hex).toList());
    } finally {
      reader.release();
    }
  }

  @Test
  void freshIterator_isNotPositioned() throws IOException {
    Path path = dir.resolve("000002.sst");
    writeTable(path, 10);

    SSTableReader reader = SSTableReader.open(path, ORDERING);
    try (InternalIterator iterator = reader.iterator()) {
      assertThat(iterator.valid()).isFalse();
    } finally {
      reader.release();
    }
  }

  @Test
  void emptyTable_isNeverValid() throws IOException {
    Path path = dir.resolve("empty.sst");
    try (SSTableWriter writer = SSTableWriter.open(path)) {
      writer.finish(); // no entries at all
    }

    SSTableReader reader = SSTableReader.open(path, ORDERING);
    try (InternalIterator iterator = reader.iterator()) {
      iterator.seekToFirst();
      assertThat(iterator.valid()).isFalse();
      iterator.seek(seek(userKey(0)));
      assertThat(iterator.valid()).isFalse();
    } finally {
      reader.release();
    }
  }

  @Test
  void seek_landsOnFirstEntryAtOrAfterTarget_atEveryKey() throws IOException {
    Path path = dir.resolve("000003.sst");
    List<byte[]> written = writeTable(path, KEYS);

    SSTableReader reader = SSTableReader.open(path, ORDERING);
    try (InternalIterator iterator = reader.iterator()) {
      // Seeking to every key in turn walks the whole key space across every block boundary,
      // which is where a two-level iterator that forgets to advance its index goes wrong.
      for (int i = 0; i < KEYS; i++) {
        iterator.seek(seek(userKey(i)));
        assertThat(iterator.valid()).as("seek to key %d", i).isTrue();
        assertThat(hex(iterator.internalKey()))
            .as("seek to key %d", i)
            .isEqualTo(hex(written.get(i)));
      }
    } finally {
      reader.release();
    }
  }

  @Test
  void seek_thenIterate_yieldsEverySubsequentKey() throws IOException {
    Path path = dir.resolve("000004.sst");
    List<byte[]> written = writeTable(path, KEYS);
    int start = KEYS / 3; // land mid-table, mid-block

    SSTableReader reader = SSTableReader.open(path, ORDERING);
    try (InternalIterator iterator = reader.iterator()) {
      List<String> read = new ArrayList<>();
      for (iterator.seek(seek(userKey(start))); iterator.valid(); iterator.next()) {
        read.add(hex(iterator.internalKey()));
      }
      assertThat(read)
          .isEqualTo(written.subList(start, KEYS).stream().map(SSTableIteratorTest::hex).toList());
    } finally {
      reader.release();
    }
  }

  @Test
  void seek_pastLastKey_isInvalid() throws IOException {
    Path path = dir.resolve("000005.sst");
    writeTable(path, KEYS);

    SSTableReader reader = SSTableReader.open(path, ORDERING);
    try (InternalIterator iterator = reader.iterator()) {
      iterator.seek(seek(bytes("~~~beyond")));
      assertThat(iterator.valid()).isFalse();
    } finally {
      reader.release();
    }
  }

  @Test
  void seek_backwards_repositions() throws IOException {
    Path path = dir.resolve("000006.sst");
    List<byte[]> written = writeTable(path, KEYS);

    SSTableReader reader = SSTableReader.open(path, ORDERING);
    try (InternalIterator iterator = reader.iterator()) {
      iterator.seek(seek(userKey(KEYS - 1)));
      iterator.seek(seek(userKey(0))); // a merge re-seeks children; it must not be one-way
      assertThat(hex(iterator.internalKey())).isEqualTo(hex(written.get(0)));
    } finally {
      reader.release();
    }
  }

  @Test
  void iteration_agreesWithCeiling() throws IOException {
    Path path = dir.resolve("000007.sst");
    writeTable(path, KEYS);

    SSTableReader reader = SSTableReader.open(path, ORDERING);
    try (InternalIterator iterator = reader.iterator()) {
      for (int i = 0; i < KEYS; i += 17) {
        SSTableReader.Entry viaCeiling = reader.ceiling(seek(userKey(i)));
        iterator.seek(seek(userKey(i)));
        assertThat(hex(iterator.internalKey())).isEqualTo(hex(viaCeiling.internalKey()));
        assertThat(iterator.value()).isEqualTo(viaCeiling.value());
      }
    } finally {
      reader.release();
    }
  }

  @Test
  void corruptDataBlock_throwsMidIteration_ratherThanTruncating() throws IOException {
    Path path = dir.resolve("000008.sst");
    writeTable(path, KEYS);

    // Corrupt a byte deep in the file — inside a later data block, past the first one, so the
    // failure can only surface once iteration has already returned entries.
    byte[] bytes = Files.readAllBytes(path);
    bytes[bytes.length / 2] ^= (byte) 0xFF;
    Files.write(path, bytes);

    SSTableReader reader = SSTableReader.open(path, ORDERING);
    try (InternalIterator iterator = reader.iterator()) {
      // N4: the damaged block must stop the scan, not be skipped to keep the read going.
      assertThatThrownBy(
              () -> {
                for (iterator.seekToFirst(); iterator.valid(); iterator.next()) {
                  iterator.internalKey();
                }
              })
          .isInstanceOf(CorruptionException.class);
    } finally {
      reader.release();
    }
  }

  @Test
  void close_isIdempotent() throws IOException {
    Path path = dir.resolve("000009.sst");
    writeTable(path, 10);

    SSTableReader reader = SSTableReader.open(path, ORDERING);
    try {
      InternalIterator iterator = reader.iterator();
      iterator.seekToFirst();
      iterator.close();
      iterator.close();
    } finally {
      reader.release();
    }
  }

  private List<byte[]> writeTable(Path path, int keys) throws IOException {
    List<byte[]> internalKeys = new ArrayList<>();
    try (SSTableWriter writer = SSTableWriter.open(path)) {
      for (int i = 0; i < keys; i++) {
        byte[] internalKey = new InternalKey(userKey(i), i + 1, ValueType.PUT).encode();
        internalKeys.add(internalKey);
        writer.add(internalKey, value(i));
      }
      writer.finish();
    }
    return internalKeys;
  }

  private static String hex(byte[] bytes) {
    return HexFormat.of().formatHex(bytes);
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
