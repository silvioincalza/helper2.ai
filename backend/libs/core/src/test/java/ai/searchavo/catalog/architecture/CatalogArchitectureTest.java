package ai.searchavo.catalog.architecture;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideOutsideOfPackages;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

class CatalogArchitectureTest {

  private static final JavaClasses CATALOG =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages("ai.searchavo.catalog");

  private static final String[] DOMAIN = {
    "ai.searchavo.catalog", "ai.searchavo.catalog.usecase", "ai.searchavo.catalog.enrichment"
  };

  @Test
  void usesOnlyItsLibraries() {
    noClasses()
        .should()
        .dependOnClassesThat(
            resideInAPackage("ai.searchavo..")
                .and(
                    resideOutsideOfPackages(
                        "ai.searchavo.catalog..",
                        "ai.searchavo.search..",
                        "ai.searchavo.platform..",
                        "ai.searchavo.shared..")))
        .check(CATALOG);
  }

  @Test
  void needsNoIdentityNorSecurity() {
    noClasses()
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("ai.searchavo.identity..", "org.springframework.security..")
        .check(CATALOG);
  }

  @Test
  void noUseCaseReachesIntoInternal() {
    noClasses()
        .that()
        .resideInAPackage("ai.searchavo.catalog.usecase..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage("..internal..")
        .check(CATALOG);
  }

  @Test
  void theCoreDoesNotDependOnItsAdapters() {
    noClasses()
        .that()
        .resideInAnyPackage(DOMAIN)
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("ai.searchavo.catalog.config..", "ai.searchavo.catalog.internal..")
        .check(CATALOG);
  }

  @Test
  void theCoreIsFrameworkFree() {
    noClasses()
        .that()
        .resideInAnyPackage(DOMAIN)
        .and()
        .haveSimpleNameNotEndingWith("package-info")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("org.springframework..", "tools.jackson..", "java.sql..")
        .check(CATALOG);
  }

  @Test
  void useCasesDoNotNameJdbcAdapters() {
    noClasses()
        .that()
        .resideInAPackage("ai.searchavo.catalog.usecase..")
        .should()
        .dependOnClassesThat()
        .haveSimpleNameStartingWith("Jdbc")
        .check(CATALOG);
  }
}
