package ai.searchavo.config;

import static org.assertj.core.api.Assertions.assertThat;

import ai.searchavo.testing.ConfigBinding;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Configuration;

class PublicPropertiesBindingTest {

  @Configuration(proxyBeanMethods = false)
  @ConfigurationPropertiesScan("ai.searchavo")
  static class EveryProperties {}

  @Test
  void everyPropertiesRecordTheAppScansBindsFromItsYaml() {
    ConfigBinding.of(EveryProperties.class).run(c -> assertThat(c).hasNotFailed());
  }
}
