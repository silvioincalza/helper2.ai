package ai.helper2.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.BackOfficeApplication;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

/**
 * Architecture rules of the back office app as tests: module boundaries (Spring Modulith),
 * hexagonal direction (ArchUnit), and none of the public site's own modules (ADR 0069). The table
 * owners are checked once, in the public app's ModularityTest.
 */
class ModularityTest {

  private static final ApplicationModules MODULES =
      ApplicationModules.of(BackOfficeApplication.class, new DoNotIncludeTestFixtures());
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
    "ai.helper2.scout",
    "ai.helper2.examples",
    "ai.helper2.search.usecase",
    "ai.helper2.scout.usecase",
    "ai.helper2.identity",
    "ai.helper2.identity.usecase",
    "ai.helper2.admin",
    "ai.helper2.admin.usecase"
  };

  @Test
  void modulesHaveNoCyclesAndUseOnlyPublicApis() {
    MODULES.verify();
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

  /** The guide, the setup guide and the SEO pages are the public app's (ADR 0069). */
  @Test
  void noPublicSiteModules() {
    assertThat(MODULES.stream().map(m -> m.getBasePackage().getName()))
        .doesNotContain("ai.helper2.guide", "ai.helper2.setup", "ai.helper2.pages");
  }
}
