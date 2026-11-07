package ai.helper2.testing;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * Where the backend's own files are, for the tests that read them. Every test runs in backend/
 * (build-logic sets the working directory).
 */
public final class BackendSources {

  /** The Flyway migrations, in the schema library. */
  public static final Path MIGRATIONS = Path.of("libs/schema/src/main/resources/db/migration");

  private static final List<Path> PROJECT_GROUPS = List.of(Path.of("libs"), Path.of("apps"));

  private BackendSources() {}

  /** One migration file by name, e.g. {@code V1__schema.sql}. */
  public static Path migration(String name) {
    return MIGRATIONS.resolve(name);
  }

  /** The main Java and Kotlin source roots of every project, e.g. libs/search/src/main/kotlin. */
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
