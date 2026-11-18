package ai.helper2.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.BackOfficeApplication;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ModularityTest {

  private static final ApplicationModules MODULES =
      ApplicationModules.of(BackOfficeApplication.class, new DoNotIncludeTestFixtures());
  private static final JavaClasses CLASSES =
      new ClassFileImporter()
          .withImportOption(new DoNotIncludeTestFixtures())
          .importPackages("ai.helper2");

  private static final String USE_CASES = "ai.helper2..usecase..";

  private static final String[] DOMAIN = {
    "ai.helper2.shared",
    "ai.helper2.search",
    "ai.helper2.scout",
    "ai.helper2.examples",
    "ai.helper2.scout.admission",
    "ai.helper2.identity",
    "ai.helper2.catalog",
    "ai.helper2.catalog.enrichment",
    "ai.helper2.admin",
    "ai.helper2.research",
    USE_CASES
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
    "ai.helper2.catalog",
    "ai.helper2.catalog.enrichment",
    "ai.helper2.scout",
    "ai.helper2.examples",
    "ai.helper2.research"
  };

  @Test
  void controllersCallUseCasesNotPorts() {
    noClasses()
        .that()
        .resideInAPackage("ai.helper2..web..")
        .should()
        .callMethodWhere(
            DescribedPredicate.describe(
                "a port of catalog, scout, examples or research",
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
        .resideInAPackage(USE_CASES)
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

  private static final String LLM = "ai.helper2.platform.llm";

  @Test
  void theLlmLayerIsUsedOnlyThroughItsNamedInterface() {
    Set<String> api =
        MODULES
            .getModuleByName("platform")
            .flatMap(platform -> platform.getNamedInterfaces().getByName("llm"))
            .orElseThrow()
            .asJavaClasses()
            .map(JavaClass::getName)
            .collect(Collectors.toSet());
    assertThat(api).contains(LLM + ".BudgetedChat");
    noClasses()
        .that()
        .resideOutsideOfPackage(LLM + "..")
        .should()
        .dependOnClassesThat(
            DescribedPredicate.describe(
                "are llm classes outside the named interface 'llm'",
                (JavaClass used) ->
                    used.getPackageName().startsWith(LLM)
                        && !api.contains(outermost(used).getName())))
        .check(CLASSES);
  }

  private static JavaClass outermost(JavaClass type) {
    return type.getEnclosingClass().map(ModularityTest::outermost).orElse(type);
  }

  @Test
  void noPublicSiteModules() {
    assertThat(MODULES.stream().map(m -> m.getBasePackage().getName()))
        .doesNotContain("ai.helper2.guide", "ai.helper2.setup", "ai.helper2.site");
  }
}
