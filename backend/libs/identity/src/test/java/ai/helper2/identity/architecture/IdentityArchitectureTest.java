package ai.helper2.identity.architecture;

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
          .importPackages("ai.helper2.identity");

  private static final String[] DOMAIN = {"ai.helper2.identity", "ai.helper2.identity.usecase"};

  @Test
  void usesOnlyItselfThePlatformAndTheKernel() {
    noClasses()
        .should()
        .dependOnClassesThat(
            resideInAPackage("ai.helper2..")
                .and(
                    resideOutsideOfPackages(
                        "ai.helper2.identity..", "ai.helper2.platform..", "ai.helper2.kernel..")))
        .check(IDENTITY);
  }

  @Test
  void noUseCaseReachesIntoInternal() {
    noClasses()
        .that()
        .resideInAPackage("ai.helper2.identity.usecase..")
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
            "ai.helper2.identity.config..",
            "ai.helper2.identity.internal..",
            "ai.helper2.identity.web..",
            "ai.helper2.identity.security..",
            "ai.helper2.identity.schedule..")
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
        .resideInAPackage("ai.helper2.identity.usecase..")
        .should()
        .dependOnClassesThat()
        .haveSimpleNameStartingWith("Jdbc")
        .check(IDENTITY);
  }
}
