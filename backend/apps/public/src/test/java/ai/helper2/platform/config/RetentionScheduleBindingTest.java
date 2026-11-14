package ai.helper2.platform.config;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.testing.ConfigBinding;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class RetentionScheduleBindingTest {

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(RetentionSchedule.class)
  static class Binding {}

  final ApplicationContextRunner context = ConfigBinding.of(Binding.class);

  @Test
  void theYamlDefaultBinds() {
    context.run(c ->
        assertThat(c.getBean(RetentionSchedule.class))
            .isEqualTo(new RetentionSchedule("0 17 3 * * *")));
  }

  @Test
  void anH2VariableReachesItsField() {
    context
        .withSystemProperties("H2_RETENTION_CRON=0 0 4 * * *")
        .run(c -> assertThat(c.getBean(RetentionSchedule.class).cron()).isEqualTo("0 0 4 * * *"));
  }

  @Test
  void aBadExpressionStopsStartup() {
    context
        .withSystemProperties("H2_RETENTION_CRON=every night")
        .run(c -> {
          assertThat(c).hasFailed();
          assertThat(c.getStartupFailure())
              .rootCause()
              .isInstanceOf(BindValidationException.class)
              .hasMessageContaining("helper2.retention.cronValid");
        });
  }
}
