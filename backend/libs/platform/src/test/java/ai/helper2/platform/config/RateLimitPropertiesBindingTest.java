package ai.helper2.platform.config;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.testing.ConfigBinding;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/** The per-client rate limits bind from application.yaml, and H2_RATE_LIMIT_* reach them. */
class RateLimitPropertiesBindingTest {

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(RateLimitProperties.class)
  static class Binding {}

  final ApplicationContextRunner context = ConfigBinding.of(Binding.class);

  @Test
  void theYamlDefaultsBind() {
    context.run(c ->
        assertThat(c.getBean(RateLimitProperties.class))
            .isEqualTo(new RateLimitProperties(60, 20, 10, 60)));
  }

  @Test
  void anH2VariableReachesItsField() {
    context
        .withSystemProperties("H2_RATE_LIMIT_GUIDE_PER_MINUTE=4")
        .run(c -> assertThat(c.getBean(RateLimitProperties.class).guidePerMinute()).isEqualTo(4));
  }

  @Test
  void aZeroLimitStopsStartup() {
    context
        .withSystemProperties("H2_RATE_LIMIT_SEARCH_PER_MINUTE=0")
        .run(c -> {
          assertThat(c).hasFailed();
          assertThat(c.getStartupFailure())
              .rootCause()
              .isInstanceOf(BindValidationException.class)
              .hasMessageContaining("helper2.rate-limit.searchPerMinute");
        });
  }
}
