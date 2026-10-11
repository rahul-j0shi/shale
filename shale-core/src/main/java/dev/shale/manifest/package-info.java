/**
 * The manifest: the durable record of which files make up the database (ADR-0012). It is a log of
 * {@link dev.shale.manifest.VersionEdit}s on the shared log framing; {@code CURRENT} names the live
 * one. Replaying the edits in order rebuilds the set of live files, which is what lets the engine
 * delete a file safely once no durable state and no reader refers to it.
 *
 * <p><b>Threading:</b> the codec, the reader and {@code CURRENT} are stateless; a {@link
 * dev.shale.manifest.ManifestWriter} has one writer. <b>Entry points:</b> {@link
 * dev.shale.manifest.ManifestWriter}, {@link dev.shale.manifest.ManifestReader}, {@link
 * dev.shale.manifest.CurrentFile}. The byte layout is {@code format.md} beside this source.
 *
 * @see <a href="https://github.com/google/leveldb/blob/main/db/version_set.cc">LevelDB
 *     version_set.cc</a>
 * @see <a href="https://github.com/google/leveldb/blob/main/db/version_edit.cc">LevelDB
 *     version_edit.cc</a>
 */
package dev.shale.manifest;
