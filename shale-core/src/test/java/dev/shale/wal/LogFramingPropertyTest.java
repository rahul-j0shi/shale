package dev.shale.wal;

import static org.assertj.core.api.Assertions.assertThat;

import dev.shale.internal.log.LogFormat;
import dev.shale.internal.log.LogReadResult;
import dev.shale.internal.log.LogWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;
import net.jqwik.api.lifecycle.AfterTry;
import net.jqwik.api.lifecycle.BeforeTry;

/**
 * Round-trip fidelity of the framing (on-disk-formats.md §3 step 6): any sequence of records, of
 * any size from empty to three blocks, written at any block position, reads back exactly, ends
 * {@code CLEAN}, and ends where the file does. The first record's size moves every later one to an
 * arbitrary position in its block, so padding of every length, and fragmentation that starts
 * anywhere, are exercised. Both framing versions are covered: v1 is still read.
 */
class LogFramingPropertyTest {

  private Path dir;

  @BeforeTry
  void makeDir() throws IOException {
    dir = Files.createTempDirectory("log-framing-property");
  }

  @AfterTry
  void cleanDir() throws IOException {
    try (var paths = Files.walk(dir)) {
      for (Path path : paths.sorted((a, b) -> b.compareTo(a)).toList()) {
        Files.delete(path);
      }
    }
  }

  @Property(tries = 150)
  void anyRecords_atAnyBlockPosition_readBackExactly(
      @ForAll @IntRange(min = 1, max = 2) int version,
      @ForAll @Size(min = 1, max = 6) List<@IntRange(min = 0, max = 100_000) Integer> sizes)
      throws IOException {
    List<byte[]> written = sizes.stream().map(size -> WalFixtures.patterned(size)).toList();
    Path path = dir.resolve("log");
    try (LogWriter writer = LogWriter.create(path, WalFormat.MAGIC, version)) {
      for (byte[] record : written) {
        writer.append(record);
      }
    }
    byte[] data = Files.readAllBytes(path);

    LogReadResult result = V2Goldens.read(data);

    assertThat(result.version()).isEqualTo(version);
    assertThat(result.records()).containsExactlyElementsOf(written);
    assertThat(result.tail()).isEqualTo(LogReadResult.Tail.CLEAN);
    assertThat(result.endOffset()).isEqualTo(data.length);
    assertThat(LogFormat.fragmentHeaderSize(version)).isPositive();
  }
}
