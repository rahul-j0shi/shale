package dev.shale.manifest;

/**
 * Constants of manifest format v2 ({@code format.md} beside this source).
 *
 * <p><b>Threading:</b> constants only.
 */
final class ManifestFormat {

  /** "ShaleMAN", as a little-endian {@code fixed64}. */
  static final long MAGIC = 0x5368616C654D414EL;

  /** The log framing version, which is the manifest's version: there is no manifest v1. */
  static final int FORMAT_VERSION = 2;

  /** Levels 0 to 6, as LevelDB's {@code config::kNumLevels}. */
  static final int NUM_LEVELS = 7;

  static final int TAG_COMPARATOR = 1;
  static final int TAG_LOG_NUMBER = 2;
  static final int TAG_NEXT_FILE_NUMBER = 3;
  static final int TAG_LAST_SEQUENCE = 4;
  static final int TAG_COMPACTION_POINTER = 5;
  static final int TAG_DELETED_FILE = 6;
  static final int TAG_NEW_FILE = 7;

  private ManifestFormat() {}
}
