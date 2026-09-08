package dev.shale.manifest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.shale.BytewiseComparator;
import dev.shale.EngineStateException;
import dev.shale.internal.key.InternalKey;
import dev.shale.internal.key.InternalKeyComparator;
import dev.shale.internal.key.ValueType;
import dev.shale.sstable.SSTableReader;
import dev.shale.sstable.SSTableWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A version is the live file set, and it owns the tables in it (ADR-0012). At M5 this is where
 * reference counting stops being hygiene: a table dropped from the live set must survive exactly as
 * long as someone is still reading it, then have its file removed.
 */
class VersionTest {

  private static final InternalKeyComparator ORDERING =
      new InternalKeyComparator(BytewiseComparator.INSTANCE);

  @TempDir private Path dir;

  @Test
  void aVersionKeepsItsTablesOpenAndReadable() throws IOException {
    SSTableReader table = writeTable(1, "a", "1");

    Version version = new Version(List.of(table));

    assertThat(version.tablesNewestFirst()).containsExactly(table);
    assertThat(table.ceiling(lookupKey("a"))).isNotNull();
    version.release();
  }

  @Test
  void releasingTheLastReferenceReleasesEveryTable() throws IOException {
    SSTableReader table = writeTable(2, "a", "1");
    Version version = new Version(List.of(table));
    table.release(); // the version now holds the only reference

    version.release();

    // The channel is closed, so a read through it fails rather than silently returning stale data.
    assertThatThrownBy(() -> table.ceiling(lookupKey("a"))).isInstanceOf(Exception.class);
  }

  @Test
  void aVersionStillReferencedKeepsItsTablesAlive() throws IOException {
    SSTableReader table = writeTable(3, "a", "1");
    Version version = new Version(List.of(table));
    table.release();

    version.retain();
    version.release(); // one reference left

    assertThat(table.ceiling(lookupKey("a"))).isNotNull();
    version.release();
  }

  @Test
  void anObsoleteTablesFileIsDeletedByItsLastRelease() throws IOException {
    SSTableReader table = writeTable(4, "a", "1");
    Path path = dir.resolve("000004.sst");
    Version version = new Version(List.of(table));
    table.release();

    // The version that superseded this one dropped the file from the live set.
    table.markObsolete();
    assertThat(Files.exists(path)).as("still live while the version holds it").isTrue();

    version.release();

    assertThat(Files.exists(path)).as("deleted once nothing references it").isFalse();
  }

  @Test
  void anObsoleteTableSurvivesWhileACursorStillPinsIt() throws IOException {
    SSTableReader table = writeTable(5, "a", "1");
    Path path = dir.resolve("000005.sst");
    Version version = new Version(List.of(table));
    table.release();

    table.retain(); // stand in for a ReconcilingCursor that opened before the install
    table.markObsolete();
    version.release();

    assertThat(Files.exists(path)).as("a reader still holds it").isTrue();
    assertThat(table.ceiling(lookupKey("a"))).isNotNull();

    table.release(); // the cursor closes

    assertThat(Files.exists(path)).as("deleted when the last reader lets go").isFalse();
  }

  @Test
  void releasingAVersionTwice_isAnEngineBugAndSaysSo() throws IOException {
    SSTableReader table = writeTable(6, "a", "1");
    Version version = new Version(List.of(table));
    version.release();

    assertThatThrownBy(version::release).isInstanceOf(EngineStateException.class);
  }

  private SSTableReader writeTable(int fileNumber, String key, String value) throws IOException {
    Path path = dir.resolve(String.format("%06d.sst", fileNumber));
    try (SSTableWriter writer = SSTableWriter.open(path)) {
      writer.add(new InternalKey(ascii(key), 1, ValueType.PUT).encode(), ascii(value));
      writer.finish();
    }
    return SSTableReader.open(path, ORDERING);
  }

  private static byte[] lookupKey(String userKey) {
    return new InternalKey(ascii(userKey), InternalKey.MAX_SEQUENCE, ValueType.FOR_SEEK).encode();
  }

  private static byte[] ascii(String value) {
    return value.getBytes(StandardCharsets.US_ASCII);
  }
}
