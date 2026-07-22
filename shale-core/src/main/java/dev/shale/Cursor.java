package dev.shale;

import dev.shale.internal.annotations.NotThreadSafe;

/**
 * A forward cursor over an ordered range of user keys. Positioned on construction at the first key
 * ≥ the scan's lower bound; {@link #isValid()} is false once exhausted.
 *
 * <p><b>Threading:</b> single-threaded; owned by the thread that opened it. The caller must {@link
 * #close()} it.
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
