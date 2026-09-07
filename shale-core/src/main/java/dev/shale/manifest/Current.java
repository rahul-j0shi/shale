package dev.shale.manifest;

import dev.shale.CorruptionException;
import dev.shale.internal.annotations.ThreadSafe;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

/**
 * The {@code CURRENT} file: one line naming the manifest in force (ADR-0012, format.md §5).
 *
 * <p>It exists so that <em>discovery</em> is atomic. A manifest is installed by writing a new one
 * and then pointing {@code CURRENT} at it with a rename, so a reader sees the old manifest or the
 * new one and never a half-built pointer. That is the same trick the SSTable flush uses: build
 * beside, then rename over.
 *
 * <p>Absence and loss are deliberately different answers. No {@code CURRENT} means a new database.
 * A {@code CURRENT} naming a manifest that is not there means a database whose spine is missing,
 * and that is {@link CorruptionException} — never an empty engine (N4). Getting this wrong is how a
 * storage engine greets a lost file by cheerfully starting over.
 *
 * <p><b>Threading:</b> stateless; writes are serialised by the version-install path that calls it.
 */
@ThreadSafe
public final class Current {

  private static final String FILE_NAME = "CURRENT";
  private static final String TEMP_FILE_NAME = "CURRENT.tmp";

  private Current() {}

  /**
   * The manifest {@code CURRENT} names, or empty if there is no {@code CURRENT} — a new database.
   *
   * @throws CorruptionException if {@code CURRENT} is empty, is not a plain file name, or names a
   *     manifest that does not exist
   */
  public static Optional<Path> read(Path directory) throws IOException {
    Path current = directory.resolve(FILE_NAME);
    if (!Files.exists(current)) {
      return Optional.empty();
    }
    String contents = Files.readString(current, StandardCharsets.UTF_8);
    String name = contents.strip();
    if (name.isEmpty()) {
      throw new CorruptionException("CURRENT is empty", 0, -1, 0);
    }
    if (!isPlainFileName(name)) {
      throw new CorruptionException(
          "CURRENT must hold a plain file name, not a path: " + name, 0, -1, -1);
    }
    Path manifest = directory.resolve(name);
    if (!Files.exists(manifest)) {
      // Not "no database" — a database whose manifest has been lost. Starting fresh here would
      // silently discard every file the manifest referenced.
      throw new CorruptionException(
          "CURRENT names a manifest that does not exist: " + name, 0, -1, -1);
    }
    return Optional.of(manifest);
  }

  /**
   * Points {@code CURRENT} at {@code manifestFileName}, atomically.
   *
   * <p>Writes {@code CURRENT.tmp}, forces it, then renames it over {@code CURRENT}. A crash before
   * the rename leaves the previous {@code CURRENT} in force and a stray temp file that {@link
   * #read} ignores; a crash after it leaves the new one. There is no in-between.
   */
  public static void write(Path directory, String manifestFileName) throws IOException {
    if (!isPlainFileName(manifestFileName)) {
      throw new IllegalArgumentException(
          "manifest name must be a plain file name: " + manifestFileName);
    }
    Path temp = directory.resolve(TEMP_FILE_NAME);
    Files.writeString(temp, manifestFileName + "\n", StandardCharsets.UTF_8);
    forceFile(temp);
    // DURABILITY: the rename is the commit point for discovery — after it, reopening finds the
    // new manifest. The temp file is forced first so the rename cannot publish unwritten bytes.
    Files.move(
        temp,
        directory.resolve(FILE_NAME),
        StandardCopyOption.ATOMIC_MOVE,
        StandardCopyOption.REPLACE_EXISTING);
    forceDirectory(directory);
  }

  /** Removes a {@code CURRENT.tmp} left by a crash mid-rewrite. Safe to call when none exists. */
  public static void deleteStrayTemp(Path directory) throws IOException {
    Files.deleteIfExists(directory.resolve(TEMP_FILE_NAME));
  }

  private static boolean isPlainFileName(String name) {
    Path asPath = Path.of(name);
    return asPath.getNameCount() == 1 && asPath.getFileName().toString().equals(name);
  }

  private static void forceFile(Path path) throws IOException {
    try (var channel =
        java.nio.channels.FileChannel.open(path, java.nio.file.StandardOpenOption.WRITE)) {
      channel.force(true);
    }
  }

  /**
   * Forces the directory entry, so the <em>rename</em> survives power loss and not merely the bytes
   * it published. Without this a crash can leave a durable new manifest that nothing points at.
   *
   * <p>Opening a directory as a channel is POSIX behaviour, not a Java guarantee: it works on Linux
   * (verified on ext4 here) and is refused on some platforms and filesystems. The failure is
   * tolerated rather than propagated, because failing to open the database at all would be a worse
   * outcome than a weaker power-loss guarantee.
   *
   * <p><b>Known limitation:</b> this cannot tell "your platform refuses directory fsync" from "your
   * disk just failed", and with no logging in {@code shale-core} (errors-and-logging.md §2 is
   * unimplemented here) a genuine I/O error is swallowed. What makes that survivable is that a
   * manifest with no CURRENT pointing at it is an orphan recovery ignores, not lost data.
   */
  private static void forceDirectory(Path directory) throws IOException {
    try (var channel =
        java.nio.channels.FileChannel.open(directory, java.nio.file.StandardOpenOption.READ)) {
      channel.force(true);
    } catch (IOException unsupportedOnThisPlatform) {
      // See the Javadoc: tolerated deliberately, with the reason it is safe to tolerate.
    }
  }
}
