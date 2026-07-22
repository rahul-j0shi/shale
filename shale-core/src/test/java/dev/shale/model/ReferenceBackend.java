package dev.shale.model;

import dev.shale.ByteRange;
import dev.shale.BytewiseComparator;
import dev.shale.Cursor;
import dev.shale.Durability;
import dev.shale.KeyComparator;
import dev.shale.StorageBackend;
import dev.shale.internal.key.InternalKey;
import dev.shale.internal.key.InternalKeyComparator;
import dev.shale.internal.key.ValueType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * In-memory {@link StorageBackend} over encoded internal keys, for driving the model harness.
 * Exercises the real M0 encoding and newest-first ordering. Non-durable: the {@link Durability}
 * argument is validated but cannot be honoured until the WAL (M1).
 *
 * <p><b>Threading:</b> single-threaded; test-owned.
 */
final class ReferenceBackend implements StorageBackend {

  private final KeyComparator userComparator = BytewiseComparator.INSTANCE;
  private final NavigableMap<byte[], byte[]> store;
  private long nextSequence = 1;

  ReferenceBackend() {
    InternalKeyComparator ikc = new InternalKeyComparator(userComparator);
    this.store = new TreeMap<>((x, y) -> ikc.compare(ByteRange.of(x), ByteRange.of(y)));
  }

  @Override
  public void put(byte[] userKey, byte[] value, Durability durability) {
    require(userKey, value, durability);
    byte[] internalKey = new InternalKey(userKey, nextSequence++, ValueType.PUT).encode();
    store.put(internalKey, value.clone());
  }

  @Override
  public void delete(byte[] userKey, Durability durability) {
    if (userKey == null) {
      throw new IllegalArgumentException("userKey is null");
    }
    if (durability == null) {
      throw new IllegalArgumentException("durability is null");
    }
    byte[] internalKey = new InternalKey(userKey, nextSequence++, ValueType.DELETE).encode();
    store.put(internalKey, new byte[0]);
  }

  @Override
  public byte[] get(byte[] userKey) {
    if (userKey == null) {
      throw new IllegalArgumentException("userKey is null");
    }
    byte[] lookup = new InternalKey(userKey, InternalKey.MAX_SEQUENCE, ValueType.FOR_SEEK).encode();
    Map.Entry<byte[], byte[]> entry = store.ceilingEntry(lookup);
    if (entry == null) {
      return null;
    }
    InternalKey found = InternalKey.decode(entry.getKey());
    if (userComparator.compare(found.userKey(), userKey) != 0) {
      return null; // no version of this user key
    }
    return found.valueType() == ValueType.PUT ? entry.getValue().clone() : null; // else tombstone
  }

  @Override
  public Cursor scan(byte[] fromInclusive, byte[] toExclusive) {
    List<byte[]> keys = new ArrayList<>();
    List<byte[]> values = new ArrayList<>();
    byte[] previousUserKey = null;
    for (Map.Entry<byte[], byte[]> entry : store.entrySet()) {
      InternalKey internalKey = InternalKey.decode(entry.getKey());
      byte[] userKey = internalKey.userKey();
      if (previousUserKey != null && userComparator.compare(userKey, previousUserKey) == 0) {
        continue; // older version of a key we already resolved
      }
      previousUserKey = userKey;
      if (fromInclusive != null && userComparator.compare(userKey, fromInclusive) < 0) {
        continue;
      }
      if (toExclusive != null && userComparator.compare(userKey, toExclusive) >= 0) {
        continue;
      }
      if (internalKey.valueType() == ValueType.DELETE) {
        continue; // tombstone hides the key
      }
      keys.add(userKey.clone());
      values.add(entry.getValue().clone());
    }
    return new ListCursor(keys, values);
  }

  @Override
  public KeyComparator comparator() {
    return userComparator;
  }

  @Override
  public void close() {
    store.clear();
  }

  private static void require(byte[] userKey, byte[] value, Durability durability) {
    if (userKey == null) {
      throw new IllegalArgumentException("userKey is null");
    }
    if (value == null) {
      throw new IllegalArgumentException("value is null");
    }
    if (durability == null) {
      throw new IllegalArgumentException("durability is null");
    }
  }

  /** Forward cursor over materialised lists. */
  private static final class ListCursor implements Cursor {
    private final List<byte[]> keys;
    private final List<byte[]> values;
    private int index;

    ListCursor(List<byte[]> keys, List<byte[]> values) {
      this.keys = keys;
      this.values = values;
    }

    @Override
    public boolean isValid() {
      return index < keys.size();
    }

    @Override
    public void next() {
      index++;
    }

    @Override
    public byte[] key() {
      return keys.get(index);
    }

    @Override
    public byte[] value() {
      return values.get(index);
    }

    @Override
    public void close() {
      // nothing to release
    }
  }
}
