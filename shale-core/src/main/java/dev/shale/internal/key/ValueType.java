package dev.shale.internal.key;

import dev.shale.CorruptionException;
import dev.shale.internal.annotations.Immutable;

/**
 * The low byte of an internal-key trailer: what a record means (ADR-0004). Codes match LevelDB's
 * {@code dbformat.h}. Codes {@code 0x02–0xFE} are reserved for types not yet invented and rejected
 * on decode; {@code 0xFF} is illegal (on-disk-formats.md §5).
 */
@Immutable
public enum ValueType {
  DELETE((byte) 0x00),
  PUT((byte) 0x01);

  /** Lookups are built with the highest type so a seek lands on the newest version. */
  public static final ValueType FOR_SEEK = PUT;

  private final byte code;

  ValueType(byte code) {
    this.code = code;
  }

  public byte code() {
    return code;
  }

  /** Decodes a trailer's type byte; a reserved or unknown code is corruption (N4). */
  public static ValueType fromCode(byte code) {
    return switch (code) {
      case 0x00 -> DELETE;
      case 0x01 -> PUT;
      default -> throw new CorruptionException("unknown value type", -1, PUT.code, code & 0xFF);
    };
  }
}
