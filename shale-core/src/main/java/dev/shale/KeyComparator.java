package dev.shale;

/**
 * Orders user keys. Pluggable and named: the {@link #name()} is persisted in the manifest (from M5)
 * so a database cannot be reopened with an incompatible ordering.
 *
 * <p><b>Threading:</b> implementations must be thread-safe and stateless.
 */
public interface KeyComparator {

  /** Total order over the two ranges; negative / zero / positive like {@code compareTo}. */
  int compare(ByteRange a, ByteRange b);

  /** Stable identity persisted with the data; changing it is a breaking change. */
  String name();

  /** Convenience overload comparing whole arrays. */
  default int compare(byte[] a, byte[] b) {
    return compare(ByteRange.of(a), ByteRange.of(b));
  }
}
