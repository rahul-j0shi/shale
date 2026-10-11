package dev.shale.manifest;

import dev.shale.internal.key.InternalKey;
import dev.shale.internal.key.ValueType;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * The edits in {@code golden/manifest/v2/three-edits.manifest}, and the worked example of {@code
 * format.md} §5. The golden was written by an implementation independent of Shale's; these values
 * are what it encodes, typed in again from {@code three-edits.manifest.json}.
 */
final class ManifestFixtures {

  /** A new database's full-state edit: the comparator, the counters, and one level-0 table. */
  static final VersionEdit OPEN =
      VersionEdit.builder()
          .comparatorName("shale.BytewiseComparator")
          .logNumber(4)
          .nextFileNumber(6)
          .lastSequence(20)
          .addFile(
              new FileMetadata(
                  0, 3, 1000, 1, 10, key("a", 3, ValueType.PUT), key("m", 10, ValueType.PUT)))
          .build();

  /** A flush: one new level-0 table, and the log number moves past the flushed segment. */
  static final VersionEdit FLUSH =
      VersionEdit.builder()
          .logNumber(7)
          .nextFileNumber(9)
          .lastSequence(35)
          .addFile(
              new FileMetadata(
                  0, 8, 2000, 21, 35, key("b", 21, ValueType.PUT), key("z", 35, ValueType.DELETE)))
          .build();

  /** A compaction as M6 will write it: two tables out, one in, and a compaction pointer. */
  static final VersionEdit COMPACTION =
      VersionEdit.builder()
          .nextFileNumber(11)
          .lastSequence(35)
          .addCompactionPointer(0, key("m", 10, ValueType.PUT))
          .deleteFile(0, 3)
          .deleteFile(0, 8)
          .addFile(
              new FileMetadata(
                  1, 10, 2900, 1, 35, key("a", 3, ValueType.PUT), key("z", 35, ValueType.DELETE)))
          .build();

  static final List<VersionEdit> THREE_EDITS = List.of(OPEN, FLUSH, COMPACTION);

  /** {@code format.md} §5: level 0, file 12, 1,234 bytes, sequences 1–2, keys a@2 to c@1. */
  static final VersionEdit WORKED_EXAMPLE =
      VersionEdit.builder()
          .addFile(
              new FileMetadata(
                  0, 12, 1234, 1, 2, key("a", 2, ValueType.PUT), key("c", 1, ValueType.PUT)))
          .build();

  private ManifestFixtures() {}

  static byte[] key(String userKey, long sequence, ValueType type) {
    return new InternalKey(userKey.getBytes(StandardCharsets.US_ASCII), sequence, type).encode();
  }
}
