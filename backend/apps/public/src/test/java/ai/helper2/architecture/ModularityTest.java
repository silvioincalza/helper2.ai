package ai.helper2.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.PublicApplication;
import ai.helper2.testing.BackendSources;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

/**
 * Architecture rules of the public app as tests: module boundaries (Spring Modulith), hexagonal
 * direction (ArchUnit), no back office code (ADR 0069), and one owner per table.
 */
class ModularityTest {

  private static final ApplicationModules MODULES =
      ApplicationModules.of(PublicApplication.class, new DoNotIncludeTestFixtures());
  private static final JavaClasses CLASSES =
      new ClassFileImporter()
          .withImportOption(new DoNotIncludeTestFixtures())
          .importPackages("ai.helper2");

  /**
   * Packages that hold the business rules: the top-level packages, their use cases and the guide's
   * answer steps — not their web/cache/config/internal adapters. Kotlin, and the Java use cases of
   * identity and admin, which reach the outside through ports.
   */
  private static final String[] DOMAIN = {
    "ai.helper2.shared",
    "ai.helper2.search",
    "ai.helper2.guide",
    "ai.helper2.setup",
    "ai.helper2.examples",
    "ai.helper2.pages",
    "ai.helper2.search.usecase",
    "ai.helper2.guide.usecase",
    "ai.helper2.guide.steps",
    "ai.helper2.identity",
    "ai.helper2.identity.usecase"
  };

  @Test
  void modulesHaveNoCyclesAndUseOnlyPublicApis() {
    MODULES.verify();
  }

  /** The back office is another app (ADR 0069): none of its code ships in the public one. */
  @Test
  void noBackOfficeCode() {
    assertThat(MODULES.stream().map(m -> m.getBasePackage().getName()))
        .doesNotContain("ai.helper2.admin", "ai.helper2.scout");
  }

  @Test
  void domainIsFrameworkFree() {
    noClasses()
        .that()
        .resideInAnyPackage(DOMAIN)
        .and()
        .haveSimpleNameNotEndingWith("package-info")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("org.springframework..", "tools.jackson..", "java.sql..")
        .check(CLASSES);
  }

  @Test
  void domainDoesNotDependOnItsAdapters() {
    noClasses()
        .that()
        .resideInAnyPackage(DOMAIN)
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            "ai.helper2..web..",
            "ai.helper2..cache..",
            "ai.helper2..config..",
            "ai.helper2..internal..")
        .check(CLASSES);
  }

  /**
   * Every use case reaches a JDBC adapter through a port, never by its class. The Java use cases of
   * catalog and examples are not in {@link #DOMAIN}: they are Spring services, and catalog's still
   * read its internal stores (roadmap).
   */
  @Test
  void useCasesDoNotNameJdbcAdapters() {
    noClasses()
        .that()
        .resideInAPackage("ai.helper2..usecase..")
        .should()
        .dependOnClassesThat()
        .haveSimpleNameStartingWith("Jdbc")
        .check(CLASSES);
  }

  /**
   * sources has no API: its base package is empty, and its adapters are wired by its own
   * configuration into the ports of search and scout. So no class outside it may name one of its
   * classes.
   */
  @Test
  void sourcesIsReachedOnlyThroughPorts() {
    noClasses()
        .that()
        .resideOutsideOfPackage("ai.helper2.sources..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage("ai.helper2.sources..")
        .check(CLASSES);
  }

  /**
   * The module that owns each table of the schema. Spring Modulith cannot see coupling through the
   * database, so this test does: every table in the versioned migrations (V*__*.sql) has one owner
   * here, and only the owner's code names it in SQL.
   */
  private static final Map<String, String> TABLE_OWNERS =
      Map.ofEntries(
          Map.entry("source", "catalog"),
          Map.entry("resource", "catalog"),
          Map.entry("resource_label", "catalog"),
          Map.entry("catalog_marker", "catalog"),
          Map.entry("source_record", "catalog"),
          Map.entry("resource_embedding", "catalog"),
          Map.entry("awesome_list_entry", "catalog"),
          Map.entry("awesome_list", "catalog"),
          Map.entry("evidence", "catalog"),
          Map.entry("enrichment_run", "catalog"),
          Map.entry("field_assertion", "catalog"),
          Map.entry("field_assertion_evidence", "catalog"),
          Map.entry("relationship", "catalog"),
          Map.entry("relationship_evidence", "catalog"),
          Map.entry("summary_claim", "catalog"),
          Map.entry("maintenance_task", "catalog"),
          Map.entry("summary_claim_evidence", "catalog"),
          Map.entry("issue_report", "catalog"),
          Map.entry("review_item", "catalog"),
          Map.entry("home_example", "examples"),
          Map.entry("app_user", "identity"),
          Map.entry("bookmark", "identity"),
          Map.entry("admin_audit", "identity"),
          Map.entry("live_web_query_result", "search"),
          Map.entry("ai_cache", "platform"),
          Map.entry("shedlock", "platform"),
          Map.entry("runtime_setting", "platform"),
          Map.entry("guide_similar_question", "guide"),
          Map.entry("source_cursor", "scout"),
          Map.entry("scout_run", "scout"),
          Map.entry("scout_schedule", "scout"),
          Map.entry("enrichment_queue_control", "scout"),
          Map.entry("discovery_host", "scout"));

  /**
   * Known exception, as "table module". The catalog's backlog query reads the Scout's
   * enrichment_queue_control, read only, so the Scout's next batch, the queue page and the count
   * keep one rule (ADR 0045 §7, BacklogRule).
   */
  private static final Set<String> SHARED_TABLES = Set.of("enrichment_queue_control catalog");

  private static final Pattern CREATE_TABLE =
      Pattern.compile("(?im)^CREATE TABLE (?:IF NOT EXISTS )?(\\w+)");

  /** A later migration may rename a table: the new name is the one that exists. */
  private static final Pattern RENAME_TABLE =
      Pattern.compile("(?im)^ALTER TABLE (\\w+) RENAME TO (\\w+)");

  /** Block and line comments: prose like "derived from source data" is not SQL. */
  private static final Pattern COMMENT = Pattern.compile("(?s)/\\*.*?\\*/|(?m)//.*$");

  private static final Pattern SQL_TABLE =
      Pattern.compile("(?i)\\b(?:FROM|JOIN|INTO|UPDATE)\\s+(\\w+)");

  @Test
  void tablesHaveOneOwner() throws IOException {
    Set<String> tables = new TreeSet<>();
    try (Stream<Path> migrations = Files.list(BackendSources.MIGRATIONS)) {
      for (Path migration :
          migrations
              .filter(m -> m.getFileName().toString().matches("V\\d+__.*\\.sql"))
              .sorted(java.util.Comparator.comparingInt(ModularityTest::version))
              .toList()) {
        String sql = Files.readString(migration);
        Matcher created = CREATE_TABLE.matcher(sql);
        while (created.find()) {
          tables.add(created.group(1).toLowerCase(java.util.Locale.ROOT));
        }
        Matcher renamed = RENAME_TABLE.matcher(sql);
        while (renamed.find()) {
          tables.remove(renamed.group(1).toLowerCase(java.util.Locale.ROOT));
          tables.add(renamed.group(2).toLowerCase(java.util.Locale.ROOT));
        }
      }
    }
    assertThat(TABLE_OWNERS.keySet())
        .as("every table has an owner")
        .containsExactlyInAnyOrderElementsOf(tables);

    List<String> foreign = new ArrayList<>();
    for (Path root :
        BackendSources.mainSourceRoots().stream()
            .map(sources -> sources.resolve("ai/helper2"))
            .filter(Files::isDirectory)
            .toList()) {
      try (Stream<Path> files = Files.walk(root)) {
        files
            .filter(Files::isRegularFile)
            .forEach(file -> {
              Path relative = root.relativize(file);
              if (relative.getNameCount() < 2) {
                return;
              }
              String module = relative.getName(0).toString();
              Matcher sql = SQL_TABLE.matcher(COMMENT.matcher(read(file)).replaceAll(" "));
              while (sql.find()) {
                String table = sql.group(1).toLowerCase(java.util.Locale.ROOT);
                String owner = TABLE_OWNERS.get(table);
                if (owner != null
                    && !owner.equals(module)
                    && !SHARED_TABLES.contains(table + " " + module)) {
                  foreign.add(relative + " uses " + table + " (owner: " + owner + ")");
                }
              }
            });
      }
    }
    assertThat(foreign).as("SQL on another module's table").isEmpty();
  }

  private static String read(Path file) {
    try {
      return Files.readString(file);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static int version(Path migration) {
    String name = migration.getFileName().toString();
    return Integer.parseInt(name.substring(1, name.indexOf("__")));
  }
}
