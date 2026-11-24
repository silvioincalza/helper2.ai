package ai.searchavo.api.architecture;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideOutsideOfPackages;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

class WebContractArchitectureTest {

  private static final JavaClasses WEB_CONTRACT =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages("ai.searchavo.api", "ai.searchavo.search.web");

  @Test
  void usesOnlyItselfSearchThePlatformAndTheKernel() {
    noClasses()
        .should()
        .dependOnClassesThat(
            resideInAPackage("ai.searchavo..")
                .and(
                    resideOutsideOfPackages(
                        "ai.searchavo.api",
                        "ai.searchavo.api.common..",
                        "ai.searchavo.search..",
                        "ai.searchavo.platform..",
                        "ai.searchavo.shared..")))
        .check(WEB_CONTRACT);
  }

  @Test
  void theContractTypesUseNothingOfOurs() {
    noClasses()
        .that()
        .resideInAPackage("ai.searchavo.api.common..")
        .should()
        .dependOnClassesThat(
            resideInAPackage("ai.searchavo..")
                .and(resideOutsideOfPackages("ai.searchavo.api.common..")))
        .check(WEB_CONTRACT);
  }
}
