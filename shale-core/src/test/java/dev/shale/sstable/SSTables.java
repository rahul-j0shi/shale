package dev.shale.sstable;

import dev.shale.iterator.InternalIterator;
import java.util.ArrayList;
import java.util.List;

/**
 * Test helper: drains a table's {@link InternalIterator} into the list that {@code entries()} used
 * to return, before M4 removed it (ADR-0011).
 *
 * <p>The golden, round-trip, and bit-flip suites all compare a table's <em>entire</em> contents
 * against a fixed expectation, which is the one job a list does better than a cursor. Tables in
 * those tests are tiny by design. Production code has no such case — that is why the accessor left
 * the public API and this stayed in the test tree.
 */
final class SSTables {

  private SSTables() {}

  /** Every entry in ascending internal-key order, copied out of the iterator. */
  static List<SSTableReader.Entry> entries(SSTableReader reader) {
    List<SSTableReader.Entry> out = new ArrayList<>();
    try (InternalIterator iterator = reader.iterator()) {
      for (iterator.seekToFirst(); iterator.valid(); iterator.next()) {
        out.add(new SSTableReader.Entry(iterator.internalKey().clone(), iterator.value().clone()));
      }
    }
    return out;
  }
}
