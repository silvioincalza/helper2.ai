package ai.helper2.platform.config;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.testing.ConfigBinding;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class BuildInfoPropertiesBindingTest {

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(BuildInfoProperties.class)
  static class Binding {}

  final ApplicationContextRunner context = ConfigBinding.of(Binding.class);

  @Test
  void theYamlDefaultBinds() {
    context.run(c ->
        assertThat(c.getBean(BuildInfoProperties.class))
            .isEqualTo(new BuildInfoProperties("local")));
  }

  @Test
  void anH2VariableReachesItsField() {
    context
        .withSystemProperties("H2_BUILD_VERSION=1.4.0-abc123")
        .run(c ->
            assertThat(c.getBean(BuildInfoProperties.class).version()).isEqualTo("1.4.0-abc123"));
  }

  @Test
  void aBlankVersionStopsStartup() {
    context
        .withPropertyValues("H2_BUILD_VERSION=")
        .run(c -> {
          assertThat(c).hasFailed();
          assertThat(c.getStartupFailure())
              .rootCause()
              .isInstanceOf(BindValidationException.class)
              .hasMessageContaining("helper2.build.version");
        });
  }
}
