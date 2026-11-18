package ai.helper2.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.PublicApplication;
import ai.helper2.testing.architecture.ModularityRules;
import org.junit.jupiter.api.Test;

class ModularityTest extends ModularityRules {

  ModularityTest() {
    super(
        PublicApplication.class,
        "ai.helper2.shared",
        "ai.helper2.search",
        "ai.helper2.guide",
        "ai.helper2.setup",
        "ai.helper2.examples",
        "ai.helper2.site",
        "ai.helper2.site.pages",
        "ai.helper2.site.monetisation",
        "ai.helper2.guide.steps",
        "ai.helper2.identity",
        "ai.helper2.catalog",
        "ai.helper2.catalog.enrichment");
  }

  @Test
  void noBackOfficeCode() {
    assertThat(modules().stream().map(m -> m.getBasePackage().getName()))
        .doesNotContain("ai.helper2.admin", "ai.helper2.scout");
  }

  @Test
  void noModuleNamesTheSite() {
    noClasses()
        .that()
        .resideOutsideOfPackages("ai.helper2.site..", "ai.helper2.architecture..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage("ai.helper2.site..")
        .check(classes());
  }
}
