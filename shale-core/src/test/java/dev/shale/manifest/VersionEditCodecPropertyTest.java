package dev.shale.manifest;

import static org.assertj.core.api.Assertions.assertThat;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Round-trip fidelity of the version-edit codec (on-disk-formats.md §3 step 6): any edit — any
 * subset of the scalar fields, any number of compaction pointers, deleted files and new files, with
 * values up to 2^63 − 1 — decodes to an equal edit and re-encodes to the same bytes.
 */
class VersionEditCodecPropertyTest {

  @Property(tries = 500)
  void anyEdit_roundTrips(@ForAll("edits") VersionEdit edit) {
    byte[] encoded = VersionEditCodec.encode(edit);

    VersionEdit decoded = VersionEditCodec.decode(encoded);

    assertThat(decoded).isEqualTo(edit);
    assertThat(VersionEditCodec.encode(decoded)).isEqualTo(encoded);
  }

  @Provide
  Arbitrary<VersionEdit> edits() {
    Arbitrary<String> names = Arbitraries.strings().ofMaxLength(40); // any Unicode, as UTF-8
    Arbitrary<Long> numbers = Arbitraries.longs().between(0, Long.MAX_VALUE);
    return Combinators.combine(
            names.injectNull(0.3),
            numbers.injectNull(0.3),
            numbers.injectNull(0.3),
            numbers.injectNull(0.3),
            pointers().list().ofMaxSize(3),
            deletions().list().ofMaxSize(4),
            files().list().ofMaxSize(4))
        .as(
            (name, log, next, last, pointers, deletions, files) -> {
              VersionEdit.Builder edit = VersionEdit.builder();
              if (name != null) {
                edit.comparatorName(name);
              }
              if (log != null) {
                edit.logNumber(log);
              }
              if (next != null) {
                edit.nextFileNumber(next);
              }
              if (last != null) {
                edit.lastSequence(last);
              }
              pointers.forEach(p -> edit.addCompactionPointer(p.level(), p.internalKey()));
              deletions.forEach(d -> edit.deleteFile(d.level(), d.number()));
              files.forEach(edit::addFile);
              return edit.build();
            });
  }

  private static Arbitrary<Integer> levels() {
    return Arbitraries.integers().between(0, ManifestFormat.NUM_LEVELS - 1);
  }

  private static Arbitrary<byte[]> internalKeys() {
    return Arbitraries.bytes().array(byte[].class).ofMinSize(8).ofMaxSize(300);
  }

  private static Arbitrary<VersionEdit.CompactionPointer> pointers() {
    return Combinators.combine(levels(), internalKeys()).as(VersionEdit.CompactionPointer::new);
  }

  private static Arbitrary<VersionEdit.DeletedFile> deletions() {
    return Combinators.combine(levels(), Arbitraries.longs().between(0, Long.MAX_VALUE))
        .as(VersionEdit.DeletedFile::new);
  }

  private static Arbitrary<FileMetadata> files() {
    Arbitrary<Long> numbers = Arbitraries.longs().between(0, Long.MAX_VALUE);
    return Combinators.combine(
            levels(), numbers, numbers, numbers, numbers, internalKeys(), internalKeys())
        .as(
            (level, number, size, a, b, smallest, largest) ->
                new FileMetadata(
                    level, number, size, Math.min(a, b), Math.max(a, b), smallest, largest));
  }
}
