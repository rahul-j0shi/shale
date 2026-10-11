package dev.shale.manifest;

import dev.shale.CorruptionException;
import dev.shale.EngineStateException;
import dev.shale.internal.annotations.Immutable;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * What a sequence of version edits adds up to: the live files and the counters. Replaying a
 * manifest's edits in order from {@link #EMPTY} rebuilds it; {@link #toEdit()} writes it back as
 * the one full-state edit a new manifest starts with. LevelDB's {@code VersionSet::Builder} plus
 * the counters LevelDB keeps beside it.
 *
 * @param comparatorName the user-key comparator's name, once an edit has named it
 * @param logNumber the oldest WAL segment still needed: older ones are already in tables
 * @param nextFileNumber the next file number to allocate
 * @param lastSequence the last sequence number made durable in a table
 * @param files the live tables, in the order the edits added them
 * @see <a href="https://github.com/google/leveldb/blob/main/db/version_set.cc">LevelDB
 *     version_set.cc (VersionSet::Builder)</a>
 */
@Immutable
public record ManifestState(
    Optional<String> comparatorName,
    long logNumber,
    long nextFileNumber,
    long lastSequence,
    List<FileMetadata> files) {

  /** No files, nothing logged; the first file number is 1. */
  public static final ManifestState EMPTY = new ManifestState(Optional.empty(), 0, 1, 0, List.of());

  /** Copies {@code files}. */
  public ManifestState {
    files = List.copyOf(files);
  }

  /** The state after every edit in {@code edits}, applied in order to {@link #EMPTY}. */
  public static ManifestState replay(List<VersionEdit> edits) {
    ManifestState state = EMPTY;
    for (VersionEdit edit : edits) {
      state = state.apply(edit);
    }
    return state;
  }

  /**
   * The state after {@code edit}: its deletions first, then its new files, then its counters.
   *
   * @throws CorruptionException if the edit deletes a file that is not live at that level, or adds
   *     a file number that is already live: a manifest that says so is not one this engine wrote
   */
  public ManifestState apply(VersionEdit edit) {
    List<FileMetadata> live = new ArrayList<>(files);
    for (VersionEdit.DeletedFile deleted : edit.deletedFiles()) {
      boolean removed =
          live.removeIf(f -> f.number() == deleted.number() && f.level() == deleted.level());
      if (!removed) {
        throw new CorruptionException(
            "version edit deletes file " + deleted.number() + ", not live at that level",
            -1,
            deleted.level(),
            deleted.number());
      }
    }
    for (FileMetadata added : edit.newFiles()) {
      if (live.stream().anyMatch(f -> f.number() == added.number())) {
        throw new CorruptionException(
            "version edit adds file " + added.number() + ", already live", -1, -1, added.number());
      }
      live.add(added);
    }
    return new ManifestState(
        edit.comparatorName().or(this::comparatorName),
        edit.logNumber().orElse(logNumber),
        edit.nextFileNumber().orElse(nextFileNumber),
        edit.lastSequence().orElse(lastSequence),
        live);
  }

  /** One edit that rebuilds this whole state from {@link #EMPTY}. */
  public VersionEdit toEdit() {
    VersionEdit.Builder edit =
        VersionEdit.builder()
            .logNumber(logNumber)
            .nextFileNumber(nextFileNumber)
            .lastSequence(lastSequence);
    comparatorName.ifPresent(edit::comparatorName);
    files.forEach(edit::addFile);
    return edit.build();
  }

  /** This state with the next file number taken: returns the state; the number is the old one. */
  ManifestState withNextFileNumberTaken() {
    if (nextFileNumber == Long.MAX_VALUE) {
      throw new EngineStateException("file number counter exhausted at " + nextFileNumber);
    }
    return new ManifestState(comparatorName, logNumber, nextFileNumber + 1, lastSequence, files);
  }
}
