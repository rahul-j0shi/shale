package dev.shale.manifest;

import dev.shale.CorruptionException;
import dev.shale.env.Env;
import dev.shale.env.WritableFile;
import dev.shale.internal.annotations.ThreadSafe;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code CURRENT}: one line naming the live manifest, {@code MANIFEST-NNNNNN\n} ({@code format.md}
 * §1). Switching it is the commit point of {@code open} and of a manifest rollover: write {@code
 * CURRENT.tmp}, sync it, rename it over {@code CURRENT}, sync the directory. A crash before the
 * rename leaves the old manifest live; after the directory sync, the new one.
 *
 * <p><b>Threading:</b> stateless; the engine calls it under its own locks.
 *
 * @see <a href="https://github.com/google/leveldb/blob/main/db/filename.cc">LevelDB filename.cc
 *     (SetCurrentFile)</a>
 */
@ThreadSafe
public final class CurrentFile {

  /** The file's name in the database directory. */
  public static final String NAME = "CURRENT";

  private static final String TEMP_NAME = "CURRENT.tmp";
  private static final Pattern CONTENT = Pattern.compile("(MANIFEST-\\d{6,})\n");
  private static final Pattern MANIFEST = Pattern.compile("MANIFEST-(\\d{6,})");

  private CurrentFile() {}

  /** {@code MANIFEST-NNNNNN} for {@code number}, at least six digits. */
  public static String manifestName(long number) {
    return String.format("MANIFEST-%06d", number);
  }

  /** The number in a manifest's name, or empty if {@code name} is not one. */
  public static Optional<Long> manifestNumber(String name) {
    Matcher matcher = MANIFEST.matcher(name);
    if (!matcher.matches()) {
      return Optional.empty();
    }
    try {
      return Optional.of(Long.parseLong(matcher.group(1)));
    } catch (NumberFormatException e) {
      return Optional.empty(); // too large to be a number this engine allocated
    }
  }

  /**
   * The manifest {@code CURRENT} in {@code directory} names, or empty if there is no {@code
   * CURRENT}.
   *
   * @throws CorruptionException naming {@code CURRENT} if its content is not one well-formed line
   */
  public static Optional<String> read(Env env, Path directory) throws IOException {
    Path current = directory.resolve(NAME);
    if (!env.exists(current)) {
      return Optional.empty();
    }
    byte[] content = env.readAllBytes(current);
    Matcher matcher = CONTENT.matcher(new String(content, StandardCharsets.ISO_8859_1));
    if (!matcher.matches() || manifestNumber(matcher.group(1)).isEmpty()) {
      throw new CorruptionException(
          current, "CURRENT is not one line naming a manifest", 0, -1, content.length);
    }
    return Optional.of(matcher.group(1));
  }

  /** Points {@code CURRENT} in {@code directory} at manifest {@code number}, durably. */
  public static void write(Env env, Path directory, long number) throws IOException {
    Path temp = directory.resolve(TEMP_NAME);
    try (WritableFile file = env.newWritableFile(temp)) {
      file.append((manifestName(number) + "\n").getBytes(StandardCharsets.US_ASCII));
      file.sync();
    }
    env.rename(temp, directory.resolve(NAME));
    // DURABILITY: once the directory sync returns, CURRENT names the new manifest after any crash.
    env.syncDirectory(directory);
  }
}
