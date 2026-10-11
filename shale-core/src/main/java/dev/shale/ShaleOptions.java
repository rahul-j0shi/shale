package dev.shale;

import dev.shale.env.Env;
import dev.shale.internal.annotations.Immutable;

/**
 * Tuning for one {@link Shale} engine. Every field has a default, and later milestones add fields,
 * so <b>build options from {@link #defaults()} and the {@code withX} methods, never the canonical
 * constructor</b>: a call to the constructor stops compiling each time a field is added.
 *
 * <p>The defaults follow LevelDB's {@code Options}: a 4 MiB write buffer ({@code
 * write_buffer_size}), a manifest that is rewritten once it passes 4 MiB, and the real file system
 * ({@code env}). Like LevelDB's, the {@link Env} is an option rather than an {@code open}
 * parameter: a test that injects faults sets it once with {@link #withEnv}.
 *
 * @param writeBufferSizeBytes the active memtable's size, in bytes, that triggers a flush
 * @param manifestRolloverBytes the manifest's size, in bytes, past which the next flush starts a
 *     new manifest holding one full-state edit (ADR-0012)
 * @param env every file operation goes through it; {@link Env#DEFAULT} is the real file system
 * @see <a href="https://github.com/google/leveldb/blob/main/include/leveldb/options.h">LevelDB
 *     options.h</a>
 */
@Immutable
public record ShaleOptions(long writeBufferSizeBytes, long manifestRolloverBytes, Env env) {

  private static final long FOUR_MIB = 4L * 1024 * 1024;

  /** Rejects a non-positive size and a null {@code env}. */
  public ShaleOptions {
    if (writeBufferSizeBytes <= 0) {
      throw new IllegalArgumentException("writeBufferSizeBytes must be positive");
    }
    if (manifestRolloverBytes <= 0) {
      throw new IllegalArgumentException("manifestRolloverBytes must be positive");
    }
    if (env == null) {
      throw new IllegalArgumentException("env must be non-null");
    }
  }

  /** A 4 MiB write buffer, a 4 MiB manifest rollover, and {@link Env#DEFAULT}. */
  public static ShaleOptions defaults() {
    return new ShaleOptions(FOUR_MIB, FOUR_MIB, Env.DEFAULT);
  }

  /** These options with {@code writeBufferSizeBytes} replaced. */
  public ShaleOptions withWriteBufferSizeBytes(long writeBufferSizeBytes) {
    return new ShaleOptions(writeBufferSizeBytes, manifestRolloverBytes, env);
  }

  /** These options with {@code manifestRolloverBytes} replaced. */
  public ShaleOptions withManifestRolloverBytes(long manifestRolloverBytes) {
    return new ShaleOptions(writeBufferSizeBytes, manifestRolloverBytes, env);
  }

  /** These options with {@code env} replaced. */
  public ShaleOptions withEnv(Env env) {
    return new ShaleOptions(writeBufferSizeBytes, manifestRolloverBytes, env);
  }
}
