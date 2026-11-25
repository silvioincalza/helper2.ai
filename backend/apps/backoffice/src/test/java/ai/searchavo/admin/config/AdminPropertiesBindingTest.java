package ai.searchavo.admin.config;

import static org.assertj.core.api.Assertions.assertThat;

import ai.searchavo.testing.ConfigBinding;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class AdminPropertiesBindingTest {

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(AdminProperties.class)
  static class Binding {}

  final ApplicationContextRunner context = ConfigBinding.of(Binding.class);

  @Test
  void theYamlDefaultBinds() {
    context.run(c ->
        assertThat(c.getBean(AdminProperties.class))
            .isEqualTo(new AdminProperties("", "https://searchavo.ai", 600, false)));
  }

  @Test
  void thePublicSiteAndTheGuideCapComeFromTheirH2Variables() {
    context
        .withSystemProperties(
            "SA_PUBLIC_BASE_URL=https://staging.example.org", "SA_GUIDE_DAILY_MODEL_CALLS=42")
        .run(c -> {
          AdminProperties admin = c.getBean(AdminProperties.class);
          assertThat(admin.publicBaseUrl()).isEqualTo("https://staging.example.org");
          assertThat(admin.guideDailyModelCalls()).isEqualTo(42);
        });
  }

  @Test
  void anH2VariableReachesItsField() {
    context
        .withSystemProperties("SA_ADMIN_GRAFANA_URL=https://grafana.example.org/d/searchavo")
        .run(c ->
            assertThat(c.getBean(AdminProperties.class).grafanaUrl())
                .isEqualTo("https://grafana.example.org/d/searchavo"));
  }
}
