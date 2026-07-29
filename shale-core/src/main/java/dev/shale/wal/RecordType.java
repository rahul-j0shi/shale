package dev.shale.wal;

import dev.shale.CorruptionException;
import dev.shale.internal.annotations.Immutable;

/**
 * The type byte of a WAL fragment (ADR-0007). {@code FULL} is a whole logical record in one
 * fragment; {@code FIRST}/{@code MIDDLE}/{@code LAST} carry a record that spans blocks. {@code
 * ZERO} is block padding and is never a real record.
 */
@Immutable
public enum RecordType {
  ZERO(0),
  FULL(1),
  FIRST(2),
  MIDDLE(3),
  LAST(4);

  private final int code;

  RecordType(int code) {
    this.code = code;
  }

  public int code() {
    return code;
  }

  /** Decodes a fragment type byte; an unknown code is corruption (N4). */
  public static RecordType fromCode(int code) {
    return switch (code) {
      case 0 -> ZERO;
      case 1 -> FULL;
      case 2 -> FIRST;
      case 3 -> MIDDLE;
      case 4 -> LAST;
      default -> throw new CorruptionException("unknown WAL record type", -1, -1, code);
    };
  }
}
