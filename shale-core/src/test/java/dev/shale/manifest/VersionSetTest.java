package dev.shale.manifest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.shale.BytewiseComparator;
import dev.shale.CorruptionException;
import dev.shale.EngineStateException;
import dev.shale.Metrics;
import dev.shale.env.Env;
import dev.shale.env.FaultInjectionEnv;
import dev.shale.internal.key.InternalKeyComparator;
import dev.shale.internal.key.ValueType;
import dev.shale.sstable.SSTableReader;
import dev.shale.sstable.SSTableWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Ownership and durability of the set of live tables (ADR-0012 §2.5; M5 Step 6). A table leaves the
 * disk exactly when it is out of the durable Version and no reader holds it; an edit that is not
 * durable is not installed; and the manifest replays to the state the set holds.
 */
class VersionSetTest {

  private static final InternalKeyComparator ORDERING =
      new InternalKeyComparator(BytewiseComparator.INSTANCE);

  @TempDir private Path root;
  private Path dir;
  private FaultInjectionEnv env;

  @BeforeEach
  void setUp() throws IOException {
    dir = root.resolve("db");
    Files.createDirectories(dir);
    env = new FaultInjectionEnv(root);
  }

  @Test
  void anObsoleteTable_isDeletedOnlyWhenItsLastReaderLetsGo() throws IOException {
    FileMetadata a = writeTable(10, "a");
    try (VersionSet versions = open(a)) {
      Version reading = versions.acquire();

      versions.logAndApply(VersionEdit.builder().deleteFile(0, 10).build(), Map.of());

      assertThat(files(versions)).isEmpty();
      assertThat(table(10)).as("a reader still holds it").exists();
      reading.release();
      assertThat(table(10)).as("out of the durable Version and unreferenced").doesNotExist();
    }
  }

  @Test
  void twoVersionsSharingAReader_keepItsFileUntilBothLetGo() throws IOException {
    FileMetadata a = writeTable(10, "a");
    try (VersionSet versions = open(a)) {
      Version first = versions.acquire();
      addTable(versions, writeTable(11, "b"));
      Version second = versions.acquire();
      assertThat(second.table(10)).as("shared, not reopened").isSameAs(first.table(10));

      versions.logAndApply(VersionEdit.builder().deleteFile(0, 10).build(), Map.of());
      first.release();
      assertThat(table(10)).as("the second Version still lists it").exists();
      second.release();

      assertThat(table(10)).doesNotExist();
      assertThat(table(11)).exists();
    }
  }

  @Test
  void aFailedManifestSync_installsNothing_andRefusesLaterEdits() throws IOException {
    FileMetadata a = writeTable(10, "a");
    try (VersionSet versions = open(a)) {
      FileMetadata b = writeTableWith(Env.DEFAULT, 11, "b"); // takes no fault-env operation
      IOException diskFull = new IOException("no space left on device");
      env.failOn(env.operationCount() + 1, diskFull); // the edit's append, then its sync

      assertThatThrownBy(() -> addTable(versions, b)).isSameAs(diskFull);

      assertThat(files(versions)).as("nothing installed").containsExactly(a);
      assertThatThrownBy(() -> addTable(versions, b))
          .isExactlyInstanceOf(EngineStateException.class)
          .hasMessageContaining("reopen to recover")
          .hasCause(diskFull);
    }
  }

  @Test
  void theManifest_replaysToTheStateTheSetHolds() throws IOException {
    FileMetadata a = writeTable(10, "a");
    try (VersionSet versions = open(a)) {
      addTable(versions, writeTable(11, "b"));
      versions.logAndApply(
          VersionEdit.builder().deleteFile(0, 10).logNumber(12).lastSequence(40).build(), Map.of());

      ManifestState replayed = VersionSet.readState(env, dir).orElseThrow();

      assertThat(replayed.files()).containsExactlyInAnyOrderElementsOf(files(versions));
      assertThat(replayed.logNumber()).isEqualTo(12);
      assertThat(replayed.lastSequence()).isEqualTo(40);
      assertThat(replayed.comparatorName()).contains("shale.BytewiseComparator");
    }
  }

  @Test
  void anEditSurvivesAPowerLoss_onceLogAndApplyReturns() throws IOException {
    FileMetadata a = writeTable(10, "a");
    VersionSet versions = open(a);
    versions.logAndApply(VersionEdit.builder().logNumber(77).build(), Map.of());

    env.dropUnsyncedData(); // the engine dies here, holding the set

    assertThat(VersionSet.readState(env, dir).orElseThrow().logNumber()).isEqualTo(77);
  }

  @Test
  void rollover_startsANewManifest_keepingEveryFile() throws IOException {
    FileMetadata a = writeTable(10, "a");
    try (VersionSet versions = open(a)) {
      addTable(versions, writeTable(11, "b"));
      long before = versions.manifestNumber();

      versions.rolloverIfLargerThan(1);

      long after = versions.manifestNumber();
      assertThat(after).isNotEqualTo(before);
      assertThat(CurrentFile.read(env, dir)).contains(CurrentFile.manifestName(after));
      assertThat(dir.resolve(CurrentFile.manifestName(before))).doesNotExist();
      assertThat(VersionSet.readState(env, dir).orElseThrow().files())
          .containsExactlyInAnyOrderElementsOf(files(versions));

      addTable(versions, writeTable(12, "c")); // the new manifest takes edits
      assertThat(VersionSet.readState(env, dir).orElseThrow().files()).hasSize(3);
    }
  }

  @Test
  void anEditDeletingAFileThatIsNotLive_isCorruption() {
    assertThatThrownBy(
            () -> ManifestState.EMPTY.apply(VersionEdit.builder().deleteFile(0, 5).build()))
        .isInstanceOf(CorruptionException.class)
        .hasMessageContaining("not live");
  }

  @Test
  void anEditAddingALiveFileNumberAgain_isCorruption() {
    FileMetadata file = ManifestFixtures.WORKED_EXAMPLE.newFiles().getFirst();
    ManifestState once = ManifestState.EMPTY.apply(VersionEdit.builder().addFile(file).build());

    assertThatThrownBy(() -> once.apply(VersionEdit.builder().addFile(file).build()))
        .isInstanceOf(CorruptionException.class)
        .hasMessageContaining("already live");
  }

  /** A set over {@code files}, as open would leave it: one full-state manifest, CURRENT on it. */
  private VersionSet open(FileMetadata... files) throws IOException {
    VersionEdit.Builder edit =
        VersionEdit.builder().comparatorName("shale.BytewiseComparator").nextFileNumber(100);
    for (FileMetadata file : files) {
      edit.addFile(file);
    }
    ManifestState state = ManifestState.EMPTY.apply(edit.build());
    ManifestWriter manifest = ManifestWriter.create(env, dir.resolve("MANIFEST-000001"));
    manifest.log(state.toEdit());
    CurrentFile.write(env, dir, 1);
    Map<Long, SSTableReader> readers = new HashMap<>();
    for (FileMetadata file : files) {
      readers.put(file.number(), SSTableReader.open(env, table(file.number()), ORDERING));
    }
    Version version = new Version(state.files(), readers);
    readers.values().forEach(SSTableReader::release); // the Version holds them now
    return new VersionSet(env, dir, Metrics.NOOP, manifest, 1, state, version);
  }

  /** Adds {@code file} through an edit, as a flush does: open its reader, apply, drop ours. */
  private void addTable(VersionSet versions, FileMetadata file) throws IOException {
    SSTableReader reader = SSTableReader.open(env, table(file.number()), ORDERING);
    try {
      versions.logAndApply(
          VersionEdit.builder().addFile(file).build(), Map.of(file.number(), reader));
    } finally {
      reader.release();
    }
  }

  /** The current Version's files, newest first. */
  private static List<FileMetadata> files(VersionSet versions) {
    Version version = versions.acquire();
    try {
      return version.files();
    } finally {
      version.release();
    }
  }

  private FileMetadata writeTable(long number, String userKey) throws IOException {
    return writeTableWith(env, number, userKey);
  }

  private FileMetadata writeTableWith(Env with, long number, String userKey) throws IOException {
    byte[] key = ManifestFixtures.key(userKey, number, ValueType.PUT);
    Path path = table(number);
    try (SSTableWriter writer = SSTableWriter.open(with, path)) {
      writer.add(key, new byte[] {1});
      writer.finish();
    }
    with.syncDirectory(dir);
    return new FileMetadata(0, number, Files.size(path), number, number, key, key);
  }

  private Path table(long number) {
    return dir.resolve(String.format("%06d.sst", number));
  }
}
