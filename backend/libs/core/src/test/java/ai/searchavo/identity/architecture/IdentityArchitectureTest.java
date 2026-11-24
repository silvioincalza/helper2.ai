package ai.searchavo.identity.architecture;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideOutsideOfPackages;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

class IdentityArchitectureTest {

  private static final JavaClasses IDENTITY =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages("ai.searchavo.identity");

  private static final String[] DOMAIN = {"ai.searchavo.identity", "ai.searchavo.identity.usecase"};

  @Test
  void usesOnlyItselfThePlatformAndTheKernel() {
    noClasses()
        .should()
        .dependOnClassesThat(
            resideInAPackage("ai.searchavo..")
                .and(
                    resideOutsideOfPackages(
                        "ai.searchavo.identity..",
                        "ai.searchavo.platform..",
                        "ai.searchavo.shared..")))
        .check(IDENTITY);
  }

  @Test
  void noUseCaseReachesIntoInternal() {
    noClasses()
        .that()
        .resideInAPackage("ai.searchavo.identity.usecase..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage("..internal..")
        .check(IDENTITY);
  }

  @Test
  void theCoreDoesNotDependOnItsAdapters() {
    noClasses()
        .that()
        .resideInAnyPackage(DOMAIN)
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            "ai.searchavo.identity.config..",
            "ai.searchavo.identity.internal..",
            "ai.searchavo.identity.web..",
            "ai.searchavo.identity.security..",
            "ai.searchavo.identity.schedule..")
        .check(IDENTITY);
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
        .check(IDENTITY);
  }

  @Test
  void useCasesDoNotNameJdbcStores() {
    noClasses()
        .that()
        .resideInAPackage("ai.searchavo.identity.usecase..")
        .should()
        .dependOnClassesThat()
        .haveSimpleNameStartingWith("Jdbc")
        .check(IDENTITY);
  }
}
