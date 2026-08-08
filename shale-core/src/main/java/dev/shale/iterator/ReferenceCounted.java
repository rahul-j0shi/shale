package dev.shale.iterator;

/**
 * A resource kept alive by counted references (N6): it survives while at least one holder has
 * {@link #retain}ed it, and is freed by the {@link #release} that drops the count to zero.
 *
 * <p>It exists so a {@link ReconcilingCursor} can pin the SSTables it reads without {@code
 * dev.shale.iterator} depending on {@code dev.shale.sstable} — which would be a cycle, since the
 * SSTable's two-level iterator implements {@link InternalIterator}. Naming the capability instead
 * of the type keeps the arrow pointing one way.
 *
 * <p>Taking and dropping references are two halves of one contract, so both live here: a cursor
 * that was handed only a way to release would depend on some other code having remembered to
 * retain, and that split is precisely how a use-after-free gets written.
 */
public interface ReferenceCounted {

  /** Adds a reference. Pair every call with exactly one {@link #release}. */
  void retain();

  /** Drops a reference; the last one frees the resource. */
  void release();
}
