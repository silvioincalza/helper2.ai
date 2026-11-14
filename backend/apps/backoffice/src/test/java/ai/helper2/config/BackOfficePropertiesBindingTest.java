package ai.helper2.config;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.testing.ConfigBinding;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Configuration;

class BackOfficePropertiesBindingTest {

  @Configuration(proxyBeanMethods = false)
  @ConfigurationPropertiesScan("ai.helper2")
  static class EveryProperties {}

  @Test
  void everyPropertiesRecordTheAppScansBindsFromItsYaml() {
    ConfigBinding.of(EveryProperties.class).run(c -> assertThat(c).hasNotFailed());
  }
}
