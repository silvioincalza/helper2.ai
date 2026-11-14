package ai.helper2.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.BackOfficeApplication;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ModularityTest {

  private static final ApplicationModules MODULES =
      ApplicationModules.of(BackOfficeApplication.class, new DoNotIncludeTestFixtures());
  private static final JavaClasses CLASSES =
      new ClassFileImporter()
          .withImportOption(new DoNotIncludeTestFixtures())
          .importPackages("ai.helper2");

  private static final String[] DOMAIN = {
    "ai.helper2.shared",
    "ai.helper2.search",
    "ai.helper2.scout",
    "ai.helper2.examples",
    "ai.helper2.examples.usecase",
    "ai.helper2.search.usecase",
    "ai.helper2.scout.usecase",
    "ai.helper2.scout.admission",
    "ai.helper2.identity",
    "ai.helper2.identity.usecase",
    "ai.helper2.catalog",
    "ai.helper2.catalog.usecase",
    "ai.helper2.catalog.enrichment",
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

  @Test
  void adminCallsOnlyTheUseCasesAndPortsOfOtherModules() {
    noClasses()
        .that()
        .resideInAPackage("ai.helper2.admin..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage("ai.helper2..internal..")
        .check(CLASSES);
  }

  private static final String[] PORT_PACKAGES = {
    "ai.helper2.catalog", "ai.helper2.catalog.enrichment", "ai.helper2.scout", "ai.helper2.examples"
  };

  @Test
  void controllersCallUseCasesNotPorts() {
    noClasses()
        .that()
        .resideInAPackage("ai.helper2..web..")
        .should()
        .callMethodWhere(
            DescribedPredicate.describe(
                "a port of catalog, scout or examples",
                (JavaMethodCall call) ->
                    call.getTargetOwner().isInterface()
                        && call.getTargetOwner().getPackage().getName().matches(portPackages())))
        .check(CLASSES);
  }

  private static String portPackages() {
    return String.join("|", PORT_PACKAGES).replace(".", "\\.");
  }

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

  @Test
  void noPublicSiteModules() {
    assertThat(MODULES.stream().map(m -> m.getBasePackage().getName()))
        .doesNotContain("ai.helper2.guide", "ai.helper2.setup", "ai.helper2.site");
  }
}
