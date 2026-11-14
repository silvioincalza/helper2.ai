package ai.helper2.platform.config;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.shared.Category;
import ai.helper2.shared.CategoryCatalog;
import ai.helper2.shared.ResourceType;
import ai.helper2.testing.ConfigBinding;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

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
}
