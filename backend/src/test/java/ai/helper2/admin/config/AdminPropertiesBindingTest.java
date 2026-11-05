package ai.helper2.admin.config;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.testing.ConfigBinding;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/** The back office's settings bind from application.yaml; an empty Grafana URL means no link. */
class AdminPropertiesBindingTest {

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(AdminProperties.class)
  static class Binding {}

  final ApplicationContextRunner context = ConfigBinding.of(Binding.class);

  @Test
  void theYamlDefaultBinds() {
    context.run(
        c -> assertThat(c.getBean(AdminProperties.class)).isEqualTo(new AdminProperties("")));
  }

  @Test
  void anH2VariableReachesItsField() {
    context
        .withSystemProperties("H2_ADMIN_GRAFANA_URL=https://grafana.example.org/d/helper2")
        .run(c ->
            assertThat(c.getBean(AdminProperties.class).grafanaUrl())
                .isEqualTo("https://grafana.example.org/d/helper2"));
  }
}
