package ai.searchavo.platform.config;

import static org.assertj.core.api.Assertions.assertThat;

import ai.searchavo.shared.Category;
import ai.searchavo.shared.CategoryCatalog;
import ai.searchavo.shared.ResourceType;
import ai.searchavo.testing.ConfigBinding;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.env.MockEnvironment;

class CategoriesPropertiesBindingTest {

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(CategoriesProperties.class)
  static class Binding {}

  final ApplicationContextRunner context = ConfigBinding.of(Binding.class);

  @Test
  void theYamlCategoriesBindInTheirOrderWithTheirRules() {
    context.run(c -> {
      CategoryCatalog catalog = c.getBean(CategoriesProperties.class).catalog();
      assertThat(catalog.ids()).hasSize(34).startsWith("prompt-libraries").endsWith("local-ai");
      Category health = catalog.get("health");
      assertThat(health.getRequireKeyword()).isTrue();
      assertThat(health.getFamily()).isEqualTo("everyday-apps");
      assertThat(catalog.get("legal").getRequireKeyword()).isTrue();
      assertThat(catalog.get("coding").getRequireKeyword()).isFalse();
      assertThat(catalog.get("prompt-libraries").getType()).isEqualTo(ResourceType.PROMPT);
      assertThat(catalog.get("image-generation").getWords().words()).contains("text to image");
    });
  }

  @Test
  void theEnvironmentBindsTheSameCategoriesAsTheBeanWithEveryDiscoveryOption() {
    context.run(c -> {
      CategoriesProperties bound = CategoriesProperties.bind(c.getEnvironment());
      assertThat(bound).isEqualTo(c.getBean(CategoriesProperties.class));
      CategoriesProperties.Discovery health =
          bound.categories().stream()
              .filter(e -> e.id().equals("health"))
              .findFirst()
              .orElseThrow()
              .discovery();
      assertThat(health.minScore()).isEqualTo(0.5);
      assertThat(health.indexLists()).isFalse();
      assertThat(health.excludeDomains()).contains("webmd.com", "nhs.uk");
    });
  }

  @Test
  void anEnvironmentWithNoCategoriesBindsAnEmptyList() {
    assertThat(CategoriesProperties.bind(new MockEnvironment()).categories()).isEmpty();
  }
}
