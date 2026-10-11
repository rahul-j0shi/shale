/**
 * The engine's I/O seam: every file the engine creates, reads, syncs, renames or deletes goes
 * through an {@link dev.shale.env.Env} (ADR-0012). The default, {@link dev.shale.env.PosixEnv},
 * wraps {@code FileChannel}. A test passes a fault-injecting {@code Env} instead, which can crash
 * the engine between any two operations and simulate a power loss by discarding what was never
 * synced — so the durability claim is tested against the exact sequence of syscalls the engine
 * makes, not argued from it.
 *
 * <p>It is public because layers above the engine must be able to pass one in: ShaleDB's crash
 * tests inject one through its own {@code open}, and {@code shale-db} may use only {@code
 * shale-core}'s public API (CLAUDE.md §2).
 *
 * <p><b>Threading:</b> an {@code Env} is thread-safe; a {@link dev.shale.env.WritableFile} has one
 * writer; a {@link dev.shale.env.ReadableFile} serves concurrent positional reads.
 * <b>Ownership:</b> every file handle an {@code Env} returns is owned by its caller and closed by
 * it (N6). <b>Entry point:</b> {@link dev.shale.env.Env#DEFAULT}.
 *
 * @see <a href="https://github.com/google/leveldb/blob/main/include/leveldb/env.h">LevelDB
 *     env.h</a>
 */
package dev.shale.env;
