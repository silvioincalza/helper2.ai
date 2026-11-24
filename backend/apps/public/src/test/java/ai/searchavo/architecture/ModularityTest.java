package ai.searchavo.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import ai.searchavo.PublicApplication;
import ai.searchavo.testing.architecture.ModularityRules;
import org.junit.jupiter.api.Test;

class ModularityTest extends ModularityRules {

  ModularityTest() {
    super(
        PublicApplication.class,
        "ai.searchavo.shared",
        "ai.searchavo.search",
        "ai.searchavo.guide",
        "ai.searchavo.setup",
        "ai.searchavo.examples",
        "ai.searchavo.site",
        "ai.searchavo.site.pages",
        "ai.searchavo.site.monetisation",
        "ai.searchavo.guide.steps",
        "ai.searchavo.identity",
        "ai.searchavo.catalog",
        "ai.searchavo.catalog.enrichment");
  }

  @Test
  void noBackOfficeCode() {
    assertThat(modules().stream().map(m -> m.getBasePackage().getName()))
        .doesNotContain("ai.searchavo.admin", "ai.searchavo.scout");
  }

  @Test
  void noModuleNamesTheSite() {
    noClasses()
        .that()
        .resideOutsideOfPackages("ai.searchavo.site..", "ai.searchavo.architecture..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage("ai.searchavo.site..")
        .check(classes());
  }
}
