package ai.helper2.catalog.architecture;

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
          .importPackages("ai.helper2.catalog");

  private static final String[] DOMAIN = {
    "ai.helper2.catalog", "ai.helper2.catalog.usecase", "ai.helper2.catalog.enrichment"
  };

  @Test
  void usesOnlyItsLibraries() {
    noClasses()
        .should()
        .dependOnClassesThat(
            resideInAPackage("ai.helper2..")
                .and(
                    resideOutsideOfPackages(
                        "ai.helper2.catalog..",
                        "ai.helper2.search..",
                        "ai.helper2.platform..",
                        "ai.helper2.shared..",
                        "ai.helper2.kernel..")))
        .check(CATALOG);
  }

  @Test
  void needsNoIdentityNorSecurity() {
    noClasses()
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("ai.helper2.identity..", "org.springframework.security..")
        .check(CATALOG);
  }

  @Test
  void noUseCaseReachesIntoInternal() {
    noClasses()
        .that()
        .resideInAPackage("ai.helper2.catalog.usecase..")
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
        .resideInAnyPackage("ai.helper2.catalog.config..", "ai.helper2.catalog.internal..")
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
        .resideInAPackage("ai.helper2.catalog.usecase..")
        .should()
        .dependOnClassesThat()
        .haveSimpleNameStartingWith("Jdbc")
        .check(CATALOG);
  }
}
