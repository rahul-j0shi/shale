package dev.shale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.shale.internal.key.InternalKey;
import dev.shale.internal.key.ValueType;
import dev.shale.wal.WalRecordCodec;
import dev.shale.wal.WalWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The recovery boundary at the engine (ADR-0024 §2, §4, §5; M4.5 Step 5): only the newest WAL
 * segment may end torn, a segment is synced when it closes, and an {@code open} that refuses its
 * inputs leaves every one of them exactly as it found it. The review's engine-level history —
 * acknowledge two writes, damage one header bit, reopen — now fails loudly with its WAL intact.
 */
class ShaleRecoveryBoundaryTest {

  @TempDir private Path dir;

  @Test
  void tornTail_inAnOlderSegment_isCorruptionNamingIt() throws IOException {
    writeSegment("000001.wal", put("A", 1), put("B", 2));
    writeSegment("000002.wal", put("C", 3));
    truncateBy(dir.resolve("000001.wal"), 3); // inside B's payload

    assertThatThrownBy(() -> Shale.open(dir, Clock.system(), Metrics.NOOP))
        .isExactlyInstanceOf(CorruptionException.class)
        .hasMessageContaining("000001.wal")
        .hasMessageContaining("not the newest");
  }

  @Test
  void tornTail_inTheNewestSegment_recoversItsPrefix() throws IOException {
    writeSegment("000001.wal", put("A", 1), put("B", 2));
    writeSegment("000002.wal", put("C", 3), put("D", 4));
    truncateBy(dir.resolve("000002.wal"), 3); // inside D's payload

    try (Shale db = Shale.open(dir, Clock.system(), Metrics.NOOP)) {
      assertThat(text(db.get(ascii("C")))).isEqualTo("v-C");
      assertThat(db.get(ascii("D"))).isNull();
      assertThat(text(db.get(ascii("A")))).isEqualTo("v-A");
    }
  }

  @Test
  void tornFileHeader_inTheNewestSegment_isAnEmptySegment() throws IOException {
    // A crash while the newest segment was being created leaves 1–15 bytes: nothing in it was
    // ever acknowledged. Until M4.5 this failed even under TRUNCATE_TAIL.
    writeSegment("000001.wal", put("A", 1));
    Files.write(dir.resolve("000002.wal"), Arrays.copyOf(segmentHeaderOnly(), 9));

    try (Shale db = Shale.open(dir, Clock.system(), Metrics.NOOP)) {
      assertThat(text(db.get(ascii("A")))).isEqualTo("v-A");
    }
  }

  @Test
  void tornFileHeader_inAnOlderSegment_isCorruption() throws IOException {
    Files.write(dir.resolve("000001.wal"), Arrays.copyOf(segmentHeaderOnly(), 9));
    writeSegment("000002.wal", put("A", 1));

    assertThatThrownBy(() -> Shale.open(dir, Clock.system(), Metrics.NOOP))
        .isExactlyInstanceOf(CorruptionException.class)
        .hasMessageContaining("000001.wal");
  }

  @Test
  void memtableSwitch_syncsTheSegmentItCloses() throws IOException {
    RecordingMetrics metrics = new RecordingMetrics();
    try (Shale db = Shale.open(dir, Clock.system(), metrics, 64)) {
      db.put(ascii("a"), new byte[100], Durability.NONE); // fills the buffer: one switch

      assertThat(metrics.counter("memtable.switch.count")).isEqualTo(1);
      // NONE writes never sync. What does: each new segment's header as it is created (the first
      // at open, the second at the switch: M5 Step 4), and the switched-out segment as it closes.
      assertThat(metrics.counter("wal.sync.count"))
          .as("two segment headers, and one close at the switch")
          .isEqualTo(3);
    }
  }

  @Test
  void reviewHistory_ackedWritesThenALengthBitFlip_failsWithItsWalIntact() throws IOException {
    // The review's engine-level reproduction, on today's (v2) segments: SYNC A and B, close, flip
    // bit 6 of byte 21 (A's length), reopen. M4 opened empty and deleted the WAL.
    try (Shale db = Shale.open(dir, Clock.system(), Metrics.NOOP)) {
      db.put(ascii("A"), ascii("ack-A"), Durability.SYNC);
      db.put(ascii("B"), ascii("ack-B"), Durability.SYNC);
    }
    flipBit(dir.resolve("000001.wal"), 21, 6);

    assertRejectedOpenChangesNothing("fragment header CRC mismatch");
  }

  @Test
  void rejectedOpen_sh08OnAV1Segment_changesNothing() throws IOException {
    copyGolden("v1/two-puts.wal");
    flipBit(dir.resolve("000001.wal"), 22, 0); // FULL -> ZERO

    assertRejectedOpenChangesNothing("ZERO");
  }

  @Test
  void rejectedOpen_sh18OnAV1Segment_changesNothing() throws IOException {
    copyGolden("v1/two-puts.wal");
    flipBit(dir.resolve("000001.wal"), 21, 6);

    assertRejectedOpenChangesNothing("unverifiable WAL tail");
  }

  @Test
  void rejectedOpen_sh18OnAV2Segment_changesNothing() throws IOException {
    copyGolden("v2/two-puts.wal");
    flipBit(dir.resolve("000001.wal"), 21, 6);

    assertRejectedOpenChangesNothing("fragment header CRC mismatch");
  }

  @Test
  void rejectedOpen_aTornOlderSegment_changesNothing() throws IOException {
    writeSegment("000001.wal", put("A", 1), put("B", 2));
    writeSegment("000002.wal", put("C", 3));
    truncateBy(dir.resolve("000001.wal"), 3);

    assertRejectedOpenChangesNothing("not the newest");
  }

  @Test
  void operatorProcedure_truncatingAnUnverifiableV1TailAsTold_acceptsTheLossAndOpens()
      throws IOException {
    // The message for SH-18 on v1 says "truncate the file to 16 bytes and reopen". Following it is
    // an explicit decision to lose that tail: here, both records. Nothing does it automatically.
    copyGolden("v1/two-puts.wal");
    Path segment = dir.resolve("000001.wal");
    flipBit(segment, 21, 6);
    assertThatThrownBy(() -> Shale.open(dir, Clock.system(), Metrics.NOOP))
        .hasMessageContaining("truncate the file to 16 bytes");

    Files.write(segment, Arrays.copyOf(Files.readAllBytes(segment), 16));

    try (Shale db = Shale.open(dir, Clock.system(), Metrics.NOOP)) {
      assertThat(db.get(ascii("A"))).isNull();
      assertThat(db.get(ascii("B"))).isNull();
    }
  }

  /** Opens {@link #dir}, expects a refusal, and checks that no file was added, removed, changed. */
  private void assertRejectedOpenChangesNothing(String reason) throws IOException {
    Map<String, String> before = snapshot();

    assertThatThrownBy(() -> Shale.open(dir, Clock.system(), Metrics.NOOP))
        .isExactlyInstanceOf(CorruptionException.class)
        .hasMessageContaining(reason);

    assertThat(snapshot())
        .as("every file name and its bytes, after the refused open")
        .isEqualTo(before);
  }

  /** File name → SHA-256 of its bytes, for every file in the directory except {@code *.tmp}. */
  private Map<String, String> snapshot() throws IOException {
    Map<String, String> files = new TreeMap<>();
    try (Stream<Path> entries = Files.list(dir)) {
      for (Path path : entries.filter(p -> !p.toString().endsWith(".tmp")).toList()) {
        files.put(path.getFileName().toString(), sha256(Files.readAllBytes(path)));
      }
    }
    return files;
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private void writeSegment(String name, byte[]... payloads) throws IOException {
    try (WalWriter writer = WalWriter.open(dir.resolve(name), Clock.system(), Metrics.NOOP)) {
      for (byte[] payload : payloads) {
        writer.append(payload, Durability.SYNC);
      }
    }
  }

  /** A v2 segment's 16-byte file header: the first 16 bytes of any v2 golden. */
  private static byte[] segmentHeaderOnly() throws IOException {
    return Arrays.copyOf(
        Files.readAllBytes(Path.of("src/test/resources/golden/wal/v2/single-put.wal")), 16);
  }

  private void copyGolden(String name) throws IOException {
    Files.copy(Path.of("src/test/resources/golden/wal/" + name), dir.resolve("000001.wal"));
  }

  private static byte[] put(String key, long sequence) {
    return WalRecordCodec.encode(
        new InternalKey(ascii(key), sequence, ValueType.PUT), ascii("v-" + key));
  }

  private static void truncateBy(Path path, int bytes) throws IOException {
    byte[] data = Files.readAllBytes(path);
    Files.write(path, Arrays.copyOf(data, data.length - bytes));
  }

  private static void flipBit(Path path, int offset, int bit) throws IOException {
    byte[] data = Files.readAllBytes(path);
    data[offset] ^= (byte) (1 << bit);
    Files.write(path, data);
  }

  private static byte[] ascii(String text) {
    return text.getBytes(StandardCharsets.US_ASCII);
  }

  private static String text(byte[] bytes) {
    return bytes == null ? null : new String(bytes, StandardCharsets.US_ASCII);
  }
}
