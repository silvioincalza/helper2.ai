package ai.searchavo.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import ai.searchavo.BackOfficeApplication;
import ai.searchavo.catalog.ReviewEvidence;
import ai.searchavo.catalog.ReviewQueue;
import ai.searchavo.catalog.enrichment.EnrichmentCandidates;
import ai.searchavo.testing.architecture.ModularityRules;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import org.junit.jupiter.api.Test;

class ModularityTest extends ModularityRules {

  ModularityTest() {
    super(
        BackOfficeApplication.class,
        "ai.searchavo.shared",
        "ai.searchavo.search",
        "ai.searchavo.scout",
        "ai.searchavo.examples",
        "ai.searchavo.scout.admission",
        "ai.searchavo.identity",
        "ai.searchavo.catalog",
        "ai.searchavo.catalog.enrichment",
        "ai.searchavo.admin",
        "ai.searchavo.research");
  }

  @Test
  void adminCallsOnlyTheUseCasesAndPortsOfOtherModules() {
    noClasses()
        .that()
        .resideInAPackage("ai.searchavo.admin..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage("ai.searchavo..internal..")
        .check(classes());
  }

  private static final String[] PORT_PACKAGES = {
    "ai.searchavo.catalog",
    "ai.searchavo.catalog.enrichment",
    "ai.searchavo.scout",
    "ai.searchavo.examples",
    "ai.searchavo.research"
  };

  @Test
  void controllersCallUseCasesNotPorts() {
    noClasses()
        .that()
        .resideInAPackage("ai.searchavo..web..")
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
        .resideInAPackage("ai.searchavo..web..")
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
        .doesNotContain("ai.searchavo.guide", "ai.searchavo.setup", "ai.searchavo.site");
  }
}
