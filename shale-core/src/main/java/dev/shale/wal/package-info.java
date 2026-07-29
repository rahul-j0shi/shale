/**
 * The write-ahead log: a LevelDB block-structured, append-only durability log (ADR-0007). A record
 * is made durable before its write is acknowledged, and on reopen the log is replayed to
 * reconstruct in-memory state.
 *
 * <p><b>Threading:</b> {@link dev.shale.wal.WalWriter} is single-writer, serialised by the engine;
 * {@link dev.shale.wal.WalReader} is stateless. <b>Entry points:</b> {@code WalWriter} and {@code
 * WalReader}. The byte layout lives in {@code format.md} beside this source.
 */
package dev.shale.wal;
