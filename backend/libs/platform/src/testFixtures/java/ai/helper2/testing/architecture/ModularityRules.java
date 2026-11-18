package ai.helper2.testing.architecture;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.assignableTo;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.belongToAnyOf;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.platform.ApiProblem;
import ai.helper2.platform.ProblemType;
import ai.helper2.platform.Problems;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.modulith.core.ApplicationModules;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class ModularityRules {

  private static final String USE_CASES = "ai.helper2..usecase..";

  private static final String LLM = "ai.helper2.platform.llm";

  private static final List<String> STILL_THROWING =
      List.of(
          "ai.helper2.catalog.usecase.AddCuratedResource",
          "ai.helper2.catalog.usecase.ChangeRelationshipTarget",
          "ai.helper2.catalog.usecase.CurateField",
          "ai.helper2.catalog.usecase.ListReviewItems",
          "ai.helper2.catalog.usecase.ResolveReviewItem",
          "ai.helper2.admin.usecase.ChangeRuntimeSetting",
          "ai.helper2.admin.usecase.DecideRelationshipItem",
          "ai.helper2.admin.usecase.DecideRelationshipTargetGroup",
          "ai.helper2.admin.usecase.KnownTargetAnswer",
          "ai.helper2.admin.usecase.ListEnrichmentQueue",
          "ai.helper2.admin.usecase.ResolveReview",
          "ai.helper2.admin.usecase.ResolveReviewItems");

  private static final DescribedPredicate<JavaClass> SPEAK_HTTP =
      assignableTo(ApiProblem.class)
          .or(belongToAnyOf(ProblemType.class, Problems.class))
          .as("speak HTTP (ApiProblem and its subtypes, ProblemType, Problems)");

  private static final DescribedPredicate<JavaClass> STILL_THROW =
      DescribedPredicate.describe(
          "are in STILL_THROWING",
          (JavaClass type) -> STILL_THROWING.contains(outermost(type).getName()));

  private final ApplicationModules modules;

  private final String[] domain;

  protected ModularityRules(Class<?> application, String... domainPackages) {
    this.modules = ApplicationModules.of(application, new DoNotIncludeTestFixtures());
    this.domain =
        Stream.concat(Arrays.stream(domainPackages), Stream.of(USE_CASES)).toArray(String[]::new);
  }

  protected ApplicationModules modules() {
    return modules;
  }

  protected JavaClasses classes() {
    return ProductionClasses.onTheClasspath();
  }

  @Test
  public void modulesHaveNoCyclesAndUseOnlyPublicApis() {
    modules.verify();
  }

  @Test
  public void domainIsFrameworkFree() {
    noClasses()
        .that()
        .resideInAnyPackage(domain)
        .and()
        .haveSimpleNameNotEndingWith("package-info")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("org.springframework..", "tools.jackson..", "java.sql..")
        .check(classes());
  }

  @Test
  public void domainDoesNotDependOnItsAdapters() {
    noClasses()
        .that()
        .resideInAnyPackage(domain)
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            "ai.helper2..web..",
            "ai.helper2..cache..",
            "ai.helper2..config..",
            "ai.helper2..internal..")
        .check(classes());
  }

  @Test
  public void useCasesDoNotNameJdbcAdapters() {
    noClasses()
        .that()
        .resideInAPackage(USE_CASES)
        .should()
        .dependOnClassesThat()
        .haveSimpleNameStartingWith("Jdbc")
        .check(classes());
  }

  @Test
  public void useCasesDoNotSpeakHttp() {
    noClasses()
        .that()
        .resideInAnyPackage(domain)
        .and(not(STILL_THROW))
        .should()
        .dependOnClassesThat(SPEAK_HTTP)
        .check(classes());
  }

  @Test
  public void everyClassInStillThrowingStillSpeaksHttp() {
    Set<String> here = outermostNames(classes().stream());
    Set<String> speaking = outermostNames(classes().stream().filter(ModularityRules::speaksHttp));
    assertThat(STILL_THROWING)
        .filteredOn(here::contains)
        .as("classes that no longer speak HTTP: take each one out of STILL_THROWING")
        .allMatch(speaking::contains);
  }

  private static boolean speaksHttp(JavaClass type) {
    return type.getDirectDependenciesFromSelf().stream()
        .anyMatch(dependency -> SPEAK_HTTP.test(dependency.getTargetClass()));
  }

  private static Set<String> outermostNames(Stream<JavaClass> types) {
    return types.map(type -> outermost(type).getName()).collect(Collectors.toSet());
  }

  @Test
  public void sourcesIsReachedOnlyThroughPorts() {
    noClasses()
        .that()
        .resideOutsideOfPackage("ai.helper2.sources..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage("ai.helper2.sources..")
        .check(classes());
  }

  @Test
  public void theLlmLayerIsUsedOnlyThroughItsNamedInterface() {
    Set<String> api =
        modules
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
        .check(classes());
  }

  private static JavaClass outermost(JavaClass type) {
    return type.getEnclosingClass().map(ModularityRules::outermost).orElse(type);
  }
}
