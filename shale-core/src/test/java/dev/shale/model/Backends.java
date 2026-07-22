package dev.shale.model;

import static org.assertj.core.api.Assertions.assertThat;

import dev.shale.Cursor;
import dev.shale.StorageBackend;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Comparison helpers: drain a {@link Cursor}, and assert a backend matches the model. */
final class Backends {

  private Backends() {}

  /** Materialises a cursor into ascending [key, value] pairs, then closes it. */
  static List<byte[][]> drain(Cursor cursor) {
    List<byte[][]> out = new ArrayList<>();
    try (cursor) {
      while (cursor.isValid()) {
        out.add(new byte[][] {cursor.key().clone(), cursor.value().clone()});
        cursor.next();
      }
    }
    return out;
  }

  /**
   * Asserts the backend agrees with the model on point lookups for every probe key and on full
   * ascending iteration. Throws {@link AssertionError} on the first divergence.
   */
  static void assertMatches(StorageBackend backend, ReferenceModel model, List<byte[]> probeKeys) {
    for (byte[] key : probeKeys) {
      assertThat(backend.get(key)).as("get(%s)", Arrays.toString(key)).isEqualTo(model.get(key));
    }
    List<byte[][]> actual = drain(backend.scan(null, null));
    List<byte[][]> expected = model.entries(null, null);
    assertThat(actual).as("full scan size").hasSameSizeAs(expected);
    for (int i = 0; i < expected.size(); i++) {
      assertThat(actual.get(i)[0]).as("scan key at index %d", i).isEqualTo(expected.get(i)[0]);
      assertThat(actual.get(i)[1]).as("scan value at index %d", i).isEqualTo(expected.get(i)[1]);
    }
  }
}
