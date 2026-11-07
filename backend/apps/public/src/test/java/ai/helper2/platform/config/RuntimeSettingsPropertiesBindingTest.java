package ai.helper2.platform.config;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.testing.ConfigBinding;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * The runtime settings' refresh and the retention of their changes bind from application.yaml (ADR
 * 0007); a refresh outside 5 s to 60 s stops startup.
 */
class RuntimeSettingsPropertiesBindingTest {

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties({
    RuntimeSettingsProperties.class,
    PlatformRetentionProperties.class
  })
  static class Binding {}

  final ApplicationContextRunner context = ConfigBinding.of(Binding.class);

  @Test
  void theYamlDefaultsBind() {
    context.run(c -> {
      assertThat(c.getBean(RuntimeSettingsProperties.class).refresh())
          .isEqualTo(Duration.ofSeconds(20));
      assertThat(c.getBean(PlatformRetentionProperties.class).runtimeSettings())
          .isEqualTo(Duration.ofDays(730));
    });
  }

  @Test
  void theH2VariablesReachTheirFields() {
    context
        .withSystemProperties(
            "H2_RUNTIME_SETTINGS_REFRESH=PT15S", "H2_RETENTION_RUNTIME_SETTINGS=P365D")
        .run(c -> {
          assertThat(c.getBean(RuntimeSettingsProperties.class).refresh())
              .isEqualTo(Duration.ofSeconds(15));
          assertThat(c.getBean(PlatformRetentionProperties.class).runtimeSettings())
              .isEqualTo(Duration.ofDays(365));
        });
  }

  @Test
  void aRefreshOutsideTheRangeStopsStartup() {
    context
        .withSystemProperties("H2_RUNTIME_SETTINGS_REFRESH=PT2S")
        .run(c -> assertThat(c).hasFailed());
  }
}
