package ai.helper2.testing;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

public final class BackendSources {
  public static final Path MIGRATIONS = Path.of("libs/schema/src/main/resources/db/migration");

  private static final List<Path> PROJECT_GROUPS = List.of(Path.of("libs"), Path.of("apps"));

  private BackendSources() {}

  public static Path migration(String name) {
    return MIGRATIONS.resolve(name);
  }

  public static List<Path> mainSourceRoots() {
    return PROJECT_GROUPS.stream()
        .flatMap(BackendSources::projects)
        .flatMap(p -> Stream.of(p.resolve("src/main/java"), p.resolve("src/main/kotlin")))
        .filter(Files::isDirectory)
        .toList();
  }

  private static Stream<Path> projects(Path group) {
    try (Stream<Path> children = Files.list(group)) {
      return children.filter(Files::isDirectory).sorted().toList().stream();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
