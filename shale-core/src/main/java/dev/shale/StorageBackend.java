package dev.shale;

/**
 * The single-node storage SPI: a durable, ordered {@code byte[]}→{@code byte[]} map. This is the
 * seam behind which the LSM engine and, at M8, a copy-on-write B+Tree both sit, so the same
 * benchmark harness can compare them (ADR-0006, roadmap §D).
 *
 * <p>Keys and values are opaque bytes; ordering is defined entirely by {@link #comparator()}. Every
 * acknowledging write takes an explicit {@link Durability} (N3).
 *
 * <p><b>Threading:</b> implementations state their own contract. <b>Ownership:</b> the caller owns
 * the instance and must {@link #close()} it.
 */
public interface StorageBackend extends AutoCloseable {

  /** Associates {@code value} with {@code userKey}, overwriting any previous value. */
  void put(byte[] userKey, byte[] value, Durability durability);

  /** Removes {@code userKey} if present; a later {@link #get} returns {@code null}. */
  void delete(byte[] userKey, Durability durability);

  /**
   * The value for {@code userKey}, or {@code null} if absent. The one documented {@code
   * null}-return path (java-style.md §6).
   */
  byte[] get(byte[] userKey);

  /**
   * An ordered cursor over user keys in {@code [fromInclusive, toExclusive)}. A {@code null} bound
   * is open-ended on that side.
   */
  Cursor scan(byte[] fromInclusive, byte[] toExclusive);

  /** The ordering this backend was opened with; its name gates reopen compatibility. */
  KeyComparator comparator();

  @Override
  void close();
}
