package dev.shale.model;

import dev.shale.Cursor;
import dev.shale.Durability;
import dev.shale.KeyComparator;
import dev.shale.StorageBackend;

/** A backend seeded with one bug — it ignores deletes — used to prove the harness bites. */
final class BuggyBackend {

  private BuggyBackend() {}

  /** Wraps a real backend but drops delete calls. */
  static StorageBackend ignoringDeletes(StorageBackend delegate) {
    return new StorageBackend() {
      @Override
      public void put(byte[] userKey, byte[] value, Durability durability) {
        delegate.put(userKey, value, durability);
      }

      @Override
      public void delete(byte[] userKey, Durability durability) {
        // BUG: intentionally ignored, so the model diverges and the harness must catch it.
      }

      @Override
      public byte[] get(byte[] userKey) {
        return delegate.get(userKey);
      }

      @Override
      public Cursor scan(byte[] fromInclusive, byte[] toExclusive) {
        return delegate.scan(fromInclusive, toExclusive);
      }

      @Override
      public KeyComparator comparator() {
        return delegate.comparator();
      }

      @Override
      public void close() {
        delegate.close();
      }
    };
  }
}
