/**
 * The log framing shared by every Shale log — the WAL now, the manifest from M5: a 16-byte file
 * header, then 32 KiB blocks of checksummed fragments that reassemble into logical records
 * (ADR-0007, amended by ADR-0024). The framing knows nothing of what a record means; each log owns
 * its magic, its record payload, and its policy for a torn tail.
 *
 * <p><b>Threading:</b> {@link dev.shale.internal.log.LogWriter} is single-writer, owned by its log;
 * {@link dev.shale.internal.log.LogReader} is stateless. <b>Entry points:</b> {@code LogWriter} and
 * {@code LogReader}. The byte layout, and how a reader classifies the end of a file, are in {@code
 * format.md} beside this source.
 *
 * @see <a href="https://github.com/google/leveldb/blob/main/db/log_format.h">LevelDB
 *     log_format.h</a>
 */
package dev.shale.internal.log;
