package dev.shale.memtable;

import static org.assertj.core.api.Assertions.assertThat;

import dev.shale.BytewiseComparator;
import dev.shale.internal.key.InternalKey;
import dev.shale.internal.key.ValueType;
import dev.shale.iterator.Drain;
import dev.shale.iterator.InternalIterator;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The {@link InternalIterator} contract as both memtables implement it (ADR-0011). Every case runs
 * against the skiplist and the {@link TreeMemtable} oracle, because the whole point of keeping the
 * M1 tree around is that a divergence between them is a skiplist bug — and an iterator that
 * disagrees with {@code ceiling} about where a seek lands would corrupt every merged read.
 */
class MemtableIteratorTest {

  static Stream<Arguments> memtables() {
    return Stream.of(
        Arguments.of(
            "skiplist",
            (Supplier<Memtable>) () -> new SkiplistMemtable(BytewiseComparator.INSTANCE, 1L)),
        Arguments.of(
            "tree", (Supplier<Memtable>) () -> new TreeMemtable(BytewiseComparator.INSTANCE)));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("memtables")
  void empty_isNeverValid(String name, Supplier<Memtable> factory) {
    Memtable memtable = factory.get();
    try (InternalIterator iterator = memtable.iterator()) {
      iterator.seekToFirst();
      assertThat(iterator.valid()).isFalse();
      iterator.seek(seek("a"));
      assertThat(iterator.valid()).isFalse();
    }
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("memtables")
  void freshIterator_isNotPositioned(String name, Supplier<Memtable> factory) {
    Memtable memtable = factory.get();
    memtable.add(put("a", 1), value("v"));
    try (InternalIterator iterator = memtable.iterator()) {
      assertThat(iterator.valid()).isFalse(); // must be positioned before it reads
    }
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("memtables")
  void seekToFirst_yieldsInternalKeyOrder_userAscSeqDesc(String name, Supplier<Memtable> factory) {
    Memtable memtable = factory.get();
    memtable.add(put("b", 5), value("b5"));
    memtable.add(put("a", 2), value("a2"));
    memtable.add(put("a", 9), value("a9")); // newer version of "a" sorts before a@2
    memtable.add(put("c", 1), value("c1"));

    try (InternalIterator iterator = memtable.iterator()) {
      assertThat(Drain.describeAll(iterator)).containsExactly("a#9", "a#2", "b#5", "c#1");
    }
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("memtables")
  void seek_landsOnFirstEntryAtOrAfterTarget(String name, Supplier<Memtable> factory) {
    Memtable memtable = factory.get();
    memtable.add(put("a", 5), value("a"));
    memtable.add(put("c", 5), value("c"));

    try (InternalIterator iterator = memtable.iterator()) {
      iterator.seek(seek("b")); // no "b": the next key in order is "c"
      assertThat(iterator.valid()).isTrue();
      assertThat(Drain.describe(iterator.internalKey())).isEqualTo("c#5");
    }
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("memtables")
  void seek_atMaxSequence_landsOnNewestVersion(String name, Supplier<Memtable> factory) {
    Memtable memtable = factory.get();
    memtable.add(put("k", 1), value("old"));
    memtable.add(put("k", 7), value("new"));

    try (InternalIterator iterator = memtable.iterator()) {
      iterator.seek(seek("k"));
      assertThat(Drain.describe(iterator.internalKey())).isEqualTo("k#7");
      assertThat(iterator.value()).isEqualTo(value("new"));

      iterator.next(); // the older version follows immediately — this is what lets a merge
      assertThat(Drain.describe(iterator.internalKey())).isEqualTo("k#1"); // take-first and skip
    }
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("memtables")
  void seek_pastLastKey_isInvalid(String name, Supplier<Memtable> factory) {
    Memtable memtable = factory.get();
    memtable.add(put("a", 5), value("a"));

    try (InternalIterator iterator = memtable.iterator()) {
      iterator.seek(seek("z"));
      assertThat(iterator.valid()).isFalse();
    }
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("memtables")
  void seek_agreesWithCeiling(String name, Supplier<Memtable> factory) {
    Memtable memtable = factory.get();
    memtable.add(put("a", 5), value("a"));
    memtable.add(put("m", 3), value("m"));
    memtable.add(put("z", 1), value("z"));

    // The point path (ceiling) and the scan path (seek) must resolve a target identically;
    // if they ever disagree, get and scan would answer differently for the same key.
    for (String target : List.of("a", "b", "m", "n", "z")) {
      Memtable.Entry viaCeiling = memtable.ceiling(seek(target));
      try (InternalIterator iterator = memtable.iterator()) {
        iterator.seek(seek(target));
        if (viaCeiling == null) {
          assertThat(iterator.valid()).as("seek(%s)", target).isFalse();
        } else {
          assertThat(Drain.describe(iterator.internalKey()))
              .as("seek(%s)", target)
              .isEqualTo(Drain.describe(viaCeiling.internalKey()));
        }
      }
    }
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("memtables")
  void iteratorReflectsTombstones_withoutInterpretingThem(String name, Supplier<Memtable> factory) {
    Memtable memtable = factory.get();
    byte[] tombstone = new InternalKey(bytes("d"), 3, ValueType.DELETE).encode();
    memtable.add(tombstone, new byte[0]);

    try (InternalIterator iterator = memtable.iterator()) {
      iterator.seekToFirst();
      // An iterator reports what is stored; hiding the key is ReconcilingCursor's job.
      assertThat(iterator.internalKey()).isEqualTo(tombstone);
      assertThat(iterator.value()).isEmpty();
    }
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("memtables")
  void close_isIdempotent(String name, Supplier<Memtable> factory) {
    Memtable memtable = factory.get();
    memtable.add(put("a", 1), value("v"));
    InternalIterator iterator = memtable.iterator();
    iterator.close();
    iterator.close(); // an in-memory source has nothing to release; twice must still be safe
  }

  private static byte[] put(String userKey, long sequence) {
    return new InternalKey(bytes(userKey), sequence, ValueType.PUT).encode();
  }

  private static byte[] seek(String userKey) {
    return new InternalKey(bytes(userKey), InternalKey.MAX_SEQUENCE, ValueType.FOR_SEEK).encode();
  }

  private static byte[] value(String value) {
    return bytes(value);
  }

  private static byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.US_ASCII);
  }
}
