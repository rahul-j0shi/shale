package dev.shale.memtable;

import dev.shale.ByteRange;
import dev.shale.KeyComparator;
import dev.shale.internal.annotations.ThreadSafe;
import dev.shale.internal.key.InternalKeyComparator;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * A hand-written skiplist memtable ordered by {@link InternalKeyComparator}. It follows LevelDB's
 * {@code db/skiplist.h}: <b>at most one writer at a time</b> (the engine serialises inserts on its
 * write lock) and <b>any number of concurrent lock-free readers</b>. Forward pointers are published
 * with release/acquire semantics, so a reader that observes a node also observes its
 * fully-initialised key and value; a reader may or may not see a concurrent insert, but never sees
 * a torn or partially-linked node (ADR-0009).
 *
 * <p>Nodes are on-heap for now; the arena-backed representation is a later, benchmark-justified
 * {@code perf} change behind this same {@link Memtable} seam.
 *
 * <p><b>Threading:</b> {@code @ThreadSafe} under the single-writer/multi-reader contract above —
 * concurrent {@code add} calls are a caller bug. <b>Deviation from the reference:</b> LevelDB
 * stores {@code max_height_} with a relaxed atomic; we use a {@code volatile} field, which is
 * stronger and equally correct.
 *
 * @see <a href="https://github.com/google/leveldb/blob/main/db/skiplist.h">LevelDB skiplist.h</a>
 */
@ThreadSafe
public final class SkiplistMemtable implements Memtable {

  /** Tower height cap; 4^12 ≈ 16M entries before the top level saturates (LevelDB's kMaxHeight). */
  private static final int MAX_HEIGHT = 12;

  /** One in {@code BRANCHING} nodes rises to the next level (LevelDB's kBranching). */
  private static final int BRANCHING = 4;

  private final InternalKeyComparator ordering;
  private final Node head = new Node(null, null, MAX_HEIGHT);

  /** Random tower heights. Writer-confined (touched only from {@code add}); reference is final. */
  private final Random random;

  /** Current max tower height in use. Volatile: publishes new express lanes to readers. */
  private volatile int height = 1;

  /** Retained bytes. Written by the single writer; volatile so a sampling reader sees it fresh. */
  private volatile long sizeBytes;

  public SkiplistMemtable(KeyComparator userComparator, long randomSeed) {
    this.ordering = new InternalKeyComparator(userComparator);
    this.random = new Random(randomSeed);
  }

  @Override
  public void add(byte[] internalKey, byte[] value) {
    byte[] key = internalKey.clone();
    byte[] storedValue = value.clone();
    Node[] prev = new Node[MAX_HEIGHT];
    findGreaterOrEqual(key, prev);

    int nodeHeight = randomHeight();
    if (nodeHeight > height) {
      for (int level = height; level < nodeHeight; level++) {
        prev[level] = head; // new express lanes start at the sentinel
      }
      height = nodeHeight; // publish the taller list before linking the node into it
    }

    Node node = new Node(key, storedValue, nodeHeight);
    for (int level = 0; level < nodeHeight; level++) {
      // The node is not yet reachable, so its own pointers need no barrier...
      node.setNextPlain(level, prev[level].nextPlain(level));
      // ...but linking it into the list must publish it with release semantics.
      prev[level].setNextRelease(level, node);
    }
    sizeBytes += (long) key.length + storedValue.length;
  }

  @Override
  public Entry ceiling(byte[] internalKey) {
    Node node = findGreaterOrEqual(internalKey, null);
    return node == null ? null : new Entry(node.internalKey, node.value);
  }

  @Override
  public List<Entry> entries() {
    List<Entry> out = new ArrayList<>();
    for (Node node = head.nextAcquire(0); node != null; node = node.nextAcquire(0)) {
      out.add(new Entry(node.internalKey, node.value));
    }
    return out;
  }

  @Override
  public long sizeBytes() {
    return sizeBytes;
  }

  /**
   * Returns the first node whose key is ≥ {@code target}, or {@code null} if none. When {@code
   * prev} is non-null it is filled with the immediate predecessor at each level (for insertion).
   */
  private Node findGreaterOrEqual(byte[] target, Node[] prev) {
    Node current = head;
    int level = height - 1;
    while (true) {
      Node next = current.nextAcquire(level);
      if (next != null && compare(next.internalKey, target) < 0) {
        current = next; // target is still ahead: stay on this level and advance
      } else {
        if (prev != null) {
          prev[level] = current;
        }
        if (level == 0) {
          return next;
        }
        level--;
      }
    }
  }

  private int compare(byte[] a, byte[] b) {
    return ordering.compare(ByteRange.of(a), ByteRange.of(b));
  }

  private int randomHeight() {
    int nodeHeight = 1;
    while (nodeHeight < MAX_HEIGHT && random.nextInt(BRANCHING) == 0) {
      nodeHeight++;
    }
    return nodeHeight;
  }

  /** A tower: an immutable key/value and one forward pointer per level it participates in. */
  private static final class Node {
    private final byte[] internalKey; // null only for the head sentinel
    private final byte[] value;
    private final AtomicReferenceArray<Node> next;

    Node(byte[] internalKey, byte[] value, int height) {
      this.internalKey = internalKey;
      this.value = value;
      this.next = new AtomicReferenceArray<>(height);
    }

    Node nextAcquire(int level) {
      return next.getAcquire(level);
    }

    void setNextRelease(int level, Node node) {
      next.setRelease(level, node);
    }

    Node nextPlain(int level) {
      return next.getPlain(level);
    }

    void setNextPlain(int level, Node node) {
      next.setPlain(level, node);
    }
  }
}
