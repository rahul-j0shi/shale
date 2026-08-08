package dev.shale.iterator;

import static org.assertj.core.api.Assertions.assertThat;

import dev.shale.BytewiseComparator;
import dev.shale.internal.key.InternalKey;
import dev.shale.internal.key.InternalKeyComparator;
import dev.shale.internal.key.ValueType;
import dev.shale.memtable.Memtable;
import dev.shale.memtable.TreeMemtable;
import java.util.ArrayList;
import java.util.List;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;

/**
 * The merge's defining property: whatever the shape of its inputs, merging sorted sequences yields
 * exactly their sorted union. Random operations are dealt across a random number of sources — which
 * generates empty sources, single-entry sources, and heavily overlapping key ranges without any of
 * them having to be enumerated by hand — and the result is compared against the same entries sorted
 * by one comparator.
 *
 * <p>Each operation's sequence number is its index, so every internal key is unique, matching the
 * engine where {@code nextSequence++} guarantees it. That is also what makes the expected order
 * total: with unique keys the sorted union has exactly one correct answer, so this asserts equality
 * rather than some weaker "is sorted" check that a merge dropping entries would still satisfy.
 */
class MergingIteratorPropertyTest {

  private static final InternalKeyComparator ORDERING =
      new InternalKeyComparator(BytewiseComparator.INSTANCE);
  private static final int USER_KEY_SPACE = 16; // small, to force overlap between sources

  @Property
  void mergeYieldsTheSortedUnionOfItsSources(
      @ForAll @Size(max = 300) List<@IntRange(min = 0, max = 2 * USER_KEY_SPACE - 1) Integer> ops,
      @ForAll @IntRange(min = 1, max = 6) int sourceCount) {
    List<Memtable> sources = new ArrayList<>();
    for (int i = 0; i < sourceCount; i++) {
      sources.add(new TreeMemtable(BytewiseComparator.INSTANCE));
    }

    List<byte[]> allKeys = new ArrayList<>();
    long sequence = 1;
    for (int i = 0; i < ops.size(); i++) {
      int op = ops.get(i);
      int userKeyId = op % USER_KEY_SPACE;
      ValueType type = op < USER_KEY_SPACE ? ValueType.PUT : ValueType.DELETE;
      byte[] internalKey = new InternalKey(userKey(userKeyId), sequence, type).encode();
      // Round-robin so sources interleave rather than each holding a contiguous key range.
      sources.get(i % sourceCount).add(internalKey, new byte[0]);
      allKeys.add(internalKey);
      sequence++;
    }

    List<String> expected =
        allKeys.stream()
            .sorted(
                (a, b) -> ORDERING.compare(dev.shale.ByteRange.of(a), dev.shale.ByteRange.of(b)))
            .map(Drain::describe)
            .toList();

    try (MergingIterator merged =
        new MergingIterator(sources.stream().map(Memtable::iterator).toList(), ORDERING)) {
      assertThat(Drain.describeAll(merged)).isEqualTo(expected);
    }
  }

  @Property
  void seekingToAnyKeyYieldsExactlyTheTailFromThere(
      @ForAll @Size(max = 200) List<@IntRange(min = 0, max = 2 * USER_KEY_SPACE - 1) Integer> ops,
      @ForAll @IntRange(min = 1, max = 4) int sourceCount,
      @ForAll @IntRange(min = 0, max = USER_KEY_SPACE - 1) int seekTo) {
    List<Memtable> sources = new ArrayList<>();
    for (int i = 0; i < sourceCount; i++) {
      sources.add(new TreeMemtable(BytewiseComparator.INSTANCE));
    }

    long sequence = 1;
    for (int i = 0; i < ops.size(); i++) {
      int userKeyId = ops.get(i) % USER_KEY_SPACE;
      byte[] internalKey = new InternalKey(userKey(userKeyId), sequence, ValueType.PUT).encode();
      sources.get(i % sourceCount).add(internalKey, new byte[0]);
      sequence++;
    }

    byte[] target =
        new InternalKey(userKey(seekTo), InternalKey.MAX_SEQUENCE, ValueType.FOR_SEEK).encode();

    // A seek must be exactly equivalent to a full scan with the prefix dropped. This is the
    // property the scan's lower bound depends on, and it is where an off-by-one in any source's
    // seek would show up as a missing or duplicated first key.
    List<String> full;
    try (MergingIterator merged =
        new MergingIterator(sources.stream().map(Memtable::iterator).toList(), ORDERING)) {
      full = Drain.describeAll(merged);
    }

    List<String> fromSeek = new ArrayList<>();
    try (MergingIterator merged =
        new MergingIterator(sources.stream().map(Memtable::iterator).toList(), ORDERING)) {
      for (merged.seek(target); merged.valid(); merged.next()) {
        fromSeek.add(Drain.describe(merged.internalKey()));
      }
    }

    // Seeking at the maximal trailer sorts before every version of the target user key, so the
    // tail is exactly the entries whose user key is >= the target's. User keys are fixed width,
    // so comparing the part before '#' is the same order the comparator uses.
    String targetUserKey = userKeyString(seekTo);
    List<String> expectedTail =
        full.stream()
            .filter(entry -> entry.substring(0, entry.indexOf('#')).compareTo(targetUserKey) >= 0)
            .toList();
    assertThat(fromSeek).isEqualTo(expectedTail);
  }

  private static String userKeyString(int id) {
    return String.format("key%03d", id);
  }

  private static byte[] userKey(int id) {
    return String.format("key%03d", id).getBytes(java.nio.charset.StandardCharsets.US_ASCII);
  }
}
