package dev.shale;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ShaleTest {

  @TempDir private Path dir;

  @Test
  void getAfterPut_returnsValueAndNullForMissing() throws IOException {
    try (Shale db = open()) {
      db.put(bytes("a"), bytes("1"), Durability.NONE);

      assertThat(db.get(bytes("a"))).isEqualTo(bytes("1"));
      assertThat(db.get(bytes("missing"))).isNull();
    }
  }

  @Test
  void overwrite_returnsNewestValue() throws IOException {
    try (Shale db = open()) {
      db.put(bytes("a"), bytes("1"), Durability.NONE);
      db.put(bytes("a"), bytes("2"), Durability.NONE);

      assertThat(db.get(bytes("a"))).isEqualTo(bytes("2"));
    }
  }

  @Test
  void delete_hidesKey() throws IOException {
    try (Shale db = open()) {
      db.put(bytes("a"), bytes("1"), Durability.NONE);
      db.delete(bytes("a"), Durability.NONE);

      assertThat(db.get(bytes("a"))).isNull();
    }
  }

  @Test
  void scan_returnsLiveKeysInOrder() throws IOException {
    try (Shale db = open()) {
      db.put(bytes("b"), bytes("2"), Durability.NONE);
      db.put(bytes("a"), bytes("1"), Durability.NONE);
      db.put(bytes("c"), bytes("3"), Durability.NONE);
      db.delete(bytes("c"), Durability.NONE);

      assertThat(scanKeys(db)).containsExactly("a", "b"); // c is a tombstone
    }
  }

  @Test
  void reopen_recoversAcknowledgedWrites() throws IOException {
    try (Shale db = open()) {
      db.put(bytes("a"), bytes("1"), Durability.SYNC);
      db.put(bytes("b"), bytes("2"), Durability.SYNC);
      db.delete(bytes("a"), Durability.SYNC);
    }

    try (Shale reopened = open()) {
      assertThat(reopened.get(bytes("a"))).isNull(); // the delete survived the restart
      assertThat(reopened.get(bytes("b"))).isEqualTo(bytes("2"));
    }
  }

  private Shale open() throws IOException {
    return Shale.open(dir, Clock.system(), Metrics.NOOP);
  }

  private static List<String> scanKeys(Shale db) {
    List<String> keys = new ArrayList<>();
    try (Cursor cursor = db.scan(null, null)) {
      while (cursor.isValid()) {
        keys.add(new String(cursor.key(), StandardCharsets.US_ASCII));
        cursor.next();
      }
    }
    return keys;
  }

  private static byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.US_ASCII);
  }
}
