// Try the Shale storage engine interactively, with nothing to install but the JDK:
//   ./gradlew :shale-core:jar
//   jshell --class-path shale-core/build/libs/shale-core-0.0.1-SNAPSHOT.jar scripts/try-shale.jsh
// It opens a database in a temporary directory, writes, reads, scans, reopens, and prints
// what it finds. Edit it, or keep typing at the jshell> prompt afterwards: `db` stays open.
import dev.shale.*;
import java.nio.file.*;
import static java.nio.charset.StandardCharsets.UTF_8;

Path dir = Files.createTempDirectory("shale-try");
Shale db = Shale.open(dir, Clock.system(), Metrics.NOOP);

db.put("apple".getBytes(UTF_8), "red".getBytes(UTF_8), Durability.SYNC);     // fsynced before it returns
db.put("banana".getBytes(UTF_8), "yellow".getBytes(UTF_8), Durability.SYNC);
db.put("cherry".getBytes(UTF_8), "dark red".getBytes(UTF_8), Durability.NONE); // survives a process crash, not power loss
db.delete("banana".getBytes(UTF_8), Durability.SYNC);                         // writes a tombstone

System.out.println("get(apple)  = " + new String(db.get("apple".getBytes(UTF_8)), UTF_8));
System.out.println("get(banana) = " + db.get("banana".getBytes(UTF_8)));      // null: deleted

try (Cursor c = db.scan(null, null)) {                                        // close cursors: they pin files
  for (; c.isValid(); c.next()) {
    System.out.println("scan: " + new String(c.key(), UTF_8) + " -> " + new String(c.value(), UTF_8));
  }
}

db.close();
db = Shale.open(dir, Clock.system(), Metrics.NOOP);                           // recovery replays the WAL
System.out.println("after reopen, get(cherry) = " + new String(db.get("cherry".getBytes(UTF_8)), UTF_8));
System.out.println("data directory: " + dir);
