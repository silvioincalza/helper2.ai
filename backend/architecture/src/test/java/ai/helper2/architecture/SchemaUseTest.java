package ai.helper2.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.TestcontainersConfiguration;
import ai.helper2.testing.BackendSources;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class SchemaUseTest {

  private static final Set<String> LIBRARY_TABLES =
      Set.of("flyway_schema_history", "shedlock", "public_app_lock");

  @Test
  void everyTableAndColumnIsUsedByTheCode() throws IOException {
    String code;
    try (Stream<Path> files =
        BackendSources.mainSourceRoots().stream().flatMap(SchemaUseTest::walk)) {
      code =
          files
              .filter(Files::isRegularFile)
              .map(SchemaUseTest::read)
              .collect(Collectors.joining("\n"));
    }
    List<String> unused =
        TestcontainersConfiguration.ownerJdbc()
            .sql(
                """
                SELECT table_name, column_name FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name <> ALL(:library)
                ORDER BY table_name, ordinal_position
                """)
            .param("library", LIBRARY_TABLES.toArray(String[]::new))
            .query((rs, i) -> rs.getString("table_name") + "." + rs.getString("column_name"))
            .list()
            .stream()
            .filter(c ->
                Arrays.stream(c.split("\\."))
                    .anyMatch(name -> !Pattern.compile("\\b" + name + "\\b").matcher(code).find()))
            .toList();
    assertThat(unused).isEmpty();
  }

  private static Stream<Path> walk(Path root) {
    try {
      return Files.walk(root);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static String read(Path file) {
    try {
      return Files.readString(file);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
