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
   * Asserts the backend agrees with the model on point lookups for every probe key, on full
   * ascending iteration, and on bounded ranges. Throws {@link AssertionError} on the first
   * divergence.
   */
  static void assertMatches(StorageBackend backend, ReferenceModel model, List<byte[]> probeKeys) {
    for (byte[] key : probeKeys) {
      assertThat(backend.get(key)).as("get(%s)", Arrays.toString(key)).isEqualTo(model.get(key));
    }
    assertScanMatches(backend, model, null, null);

    // Bounded scans exercise the seek-per-source path, which a full scan never touches: a full
    // scan starts every source at its first entry, so a bug in the lower-bound seek — landing one
    // entry late, or missing an inclusive bound — would be invisible here without these.
    for (byte[] key : probeKeys) {
      assertScanMatches(backend, model, key, null); // from the key onward
      assertScanMatches(backend, model, null, key); // up to the key
      assertScanMatches(backend, model, key, key); // empty range
    }
    for (int i = 0; i + 1 < probeKeys.size(); i++) {
      byte[] low = probeKeys.get(i);
      byte[] high = probeKeys.get(i + 1);
      if (compare(low, high) <= 0) {
        assertScanMatches(backend, model, low, high);
      }
    }
  }

  /** Asserts one range agrees, key for key and value for value. */
  private static void assertScanMatches(
      StorageBackend backend, ReferenceModel model, byte[] fromInclusive, byte[] toExclusive) {
    List<byte[][]> actual = drain(backend.scan(fromInclusive, toExclusive));
    List<byte[][]> expected = model.entries(fromInclusive, toExclusive);
    String range = Arrays.toString(fromInclusive) + ".." + Arrays.toString(toExclusive);
    assertThat(actual).as("scan %s size", range).hasSameSizeAs(expected);
    for (int i = 0; i < expected.size(); i++) {
      assertThat(actual.get(i)[0]).as("scan %s key at %d", range, i).isEqualTo(expected.get(i)[0]);
      assertThat(actual.get(i)[1])
          .as("scan %s value at %d", range, i)
          .isEqualTo(expected.get(i)[1]);
    }
  }

  private static int compare(byte[] a, byte[] b) {
    return Arrays.compareUnsigned(a, b);
  }
}
