package ai.helper2.api.architecture;

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
          .importPackages("ai.helper2.api", "ai.helper2.search.web");

  @Test
  void usesOnlyItselfSearchThePlatformAndTheKernel() {
    noClasses()
        .should()
        .dependOnClassesThat(
            resideInAPackage("ai.helper2..")
                .and(
                    resideOutsideOfPackages(
                        "ai.helper2.api",
                        "ai.helper2.api.common..",
                        "ai.helper2.search..",
                        "ai.helper2.platform..",
                        "ai.helper2.shared..")))
        .check(WEB_CONTRACT);
  }

  @Test
  void theContractTypesUseNothingOfOurs() {
    noClasses()
        .that()
        .resideInAPackage("ai.helper2.api.common..")
        .should()
        .dependOnClassesThat(
            resideInAPackage("ai.helper2..")
                .and(resideOutsideOfPackages("ai.helper2.api.common..")))
        .check(WEB_CONTRACT);
  }
}
