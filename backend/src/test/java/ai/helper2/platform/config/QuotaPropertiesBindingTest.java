package ai.helper2.platform.config;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.testing.ConfigBinding;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/** The quota key binds from H2_QUOTA_KEY_SECRET, empty by default, and is never printed. */
class QuotaPropertiesBindingTest {

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(QuotaProperties.class)
  static class Binding {}

  final ApplicationContextRunner context = ConfigBinding.of(Binding.class);

  @Test
  void theYamlDefaultIsEmpty() {
    context.run(
        c -> assertThat(c.getBean(QuotaProperties.class)).isEqualTo(new QuotaProperties("")));
  }

  @Test
  void anH2VariableReachesItsFieldAndIsMasked() {
    context
        .withSystemProperties("H2_QUOTA_KEY_SECRET=hmac-secret")
        .run(c -> {
          QuotaProperties quota = c.getBean(QuotaProperties.class);
          assertThat(quota.keySecret()).isEqualTo("hmac-secret");
          assertThat(quota.toString()).doesNotContain("hmac-secret").contains("keySecret=***");
        });
  }
}
