package dev.shale;

import dev.shale.internal.annotations.NotThreadSafe;

/**
 * A forward cursor over an ordered range of user keys. Positioned on construction at the first key
 * ≥ the scan's lower bound; {@link #isValid()} is false once exhausted.
 *
 * <p><b>Threading:</b> single-threaded; owned by the thread that opened it.
 *
 * <p><b>Closing is mandatory, not housekeeping.</b> From M4 a cursor streams from live sources and
 * holds a reference to every SSTable it can read from for its whole life (N6, ADR-0011); an
 * unclosed cursor pins file handles. Use try-with-resources.
 *
 * <p>The arrays returned by {@link #key()} and {@link #value()} are the caller's to keep — they are
 * copied out and stay valid after {@link #next()}.
 */
@NotThreadSafe
public interface Cursor extends AutoCloseable {

  /** True while positioned on a live entry; false once the range is exhausted. */
  boolean isValid();

  /** Advances to the next user key in ascending order. */
  void next();

  /** The current user key. Callers must not mutate the returned array. */
  byte[] key();

  /** The current value. Callers must not mutate the returned array. */
  byte[] value();

  @Override
  void close();
}
