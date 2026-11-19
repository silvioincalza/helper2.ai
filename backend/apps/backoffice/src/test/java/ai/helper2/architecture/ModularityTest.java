package ai.helper2.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.BackOfficeApplication;
import ai.helper2.catalog.ReviewEvidence;
import ai.helper2.catalog.ReviewQueue;
import ai.helper2.catalog.enrichment.EnrichmentCandidates;
import ai.helper2.testing.architecture.ModularityRules;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import org.junit.jupiter.api.Test;

class ModularityTest extends ModularityRules {

  ModularityTest() {
    super(
        BackOfficeApplication.class,
        "ai.helper2.shared",
        "ai.helper2.search",
        "ai.helper2.scout",
        "ai.helper2.examples",
        "ai.helper2.scout.admission",
        "ai.helper2.identity",
        "ai.helper2.catalog",
        "ai.helper2.catalog.enrichment",
        "ai.helper2.admin",
        "ai.helper2.research");
  }

  @Test
  void adminCallsOnlyTheUseCasesAndPortsOfOtherModules() {
    noClasses()
        .that()
        .resideInAPackage("ai.helper2.admin..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage("ai.helper2..internal..")
        .check(classes());
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
        .check(classes());
  }

  @Test
  void controllersAnswerWithViewsNotPortRecords() {
    noClasses()
        .that()
        .resideInAPackage("ai.helper2..web..")
        .should()
        .dependOnClassesThat()
        .belongToAnyOf(
            ReviewQueue.Status.class,
            ReviewQueue.Item.class,
            EnrichmentCandidates.View.class,
            EnrichmentCandidates.BacklogEntry.class,
            ReviewEvidence.Evidence.class)
        .check(classes());
  }

  private static String portPackages() {
    return String.join("|", PORT_PACKAGES).replace(".", "\\.");
  }

  @Test
  void noPublicSiteModules() {
    assertThat(modules().stream().map(m -> m.getBasePackage().getName()))
        .doesNotContain("ai.helper2.guide", "ai.helper2.setup", "ai.helper2.site");
  }
}
