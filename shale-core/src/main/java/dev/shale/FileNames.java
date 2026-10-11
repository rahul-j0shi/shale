package dev.shale;

import dev.shale.internal.annotations.ThreadSafe;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The names of the engine's files (naming.md §7; ADR-0012 §2.1). Every kind takes its number from
 * one counter and writes it {@code %06d} — a <em>minimum</em> width, so number 1,000,000 has seven
 * digits. Names are therefore matched as {@code \d{6,}} and ordered by number, never as strings
 * (SH-09).
 *
 * <p><b>Threading:</b> stateless.
 *
 * @see <a href="https://github.com/google/leveldb/blob/main/db/filename.cc">LevelDB filename.cc</a>
 */
@ThreadSafe
final class FileNames {

  static final Pattern WAL = Pattern.compile("(\\d{6,})\\.wal");
  static final Pattern SSTABLE = Pattern.compile("(\\d{6,})\\.sst");
  static final Pattern MANIFEST = Pattern.compile("MANIFEST-(\\d{6,})");

  private FileNames() {}

  static String wal(long number) {
    return String.format("%06d.wal", number);
  }

  static String sstable(long number) {
    return String.format("%06d.sst", number);
  }

  /** Whether {@code name} is a file of the kind {@code pattern} matches. */
  static boolean is(String name, Pattern pattern) {
    return pattern.matcher(name).matches();
  }

  /**
   * The number in {@code name}, which must match {@code pattern}.
   *
   * @throws EngineStateException if the number does not fit in a {@code long}: no counter of this
   *     engine produced it
   */
  static long numberOf(String name, Pattern pattern) {
    Matcher matcher = pattern.matcher(name);
    if (!matcher.matches()) {
      throw new IllegalArgumentException("not an engine file name: " + name);
    }
    try {
      return Long.parseLong(matcher.group(1));
    } catch (NumberFormatException e) {
      throw new EngineStateException("file number out of range in " + name, e);
    }
  }

  /** {@code number + 1}; the counter never wraps to a number already used. */
  static long nextAfter(long number) {
    if (number == Long.MAX_VALUE) {
      throw new EngineStateException("file number counter exhausted at " + number);
    }
    return number + 1;
  }
}
