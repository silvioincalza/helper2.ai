package ai.helper2.site.pages.config;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.testing.ConfigBinding;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class PagesPropertiesBindingTest {

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(PagesProperties.class)
  static class Binding {}

  final ApplicationContextRunner context = ConfigBinding.of(Binding.class);

  @Test
  void theShellIsTheFileTheInitContainerCopies() {
    context.run(c ->
        assertThat(c.getBean(PagesProperties.class).template())
            .isEqualTo("file:/shell/index.html"));
  }

  @Test
  void anotherShellComesFromTheEnvironment() {
    context
        .withSystemProperties("H2_PAGES_TEMPLATE=file:/tmp/shell/index.html")
        .run(c ->
            assertThat(c.getBean(PagesProperties.class).template())
                .isEqualTo("file:/tmp/shell/index.html"));
  }
}
