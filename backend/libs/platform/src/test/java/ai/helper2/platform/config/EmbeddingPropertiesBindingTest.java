package ai.helper2.platform.config;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.testing.ConfigBinding;
import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class EmbeddingPropertiesBindingTest {

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(EmbeddingProperties.class)
  static class Binding {}

  final ApplicationContextRunner context = ConfigBinding.of(Binding.class);

  @Test
  void theYamlDefaultsBind() {
    context.run(c -> {
      EmbeddingProperties embedding = c.getBean(EmbeddingProperties.class);
      assertThat(embedding)
          .isEqualTo(
              new EmbeddingProperties(
                  URI.create("https://integrate.api.nvidia.com/v1"),
                  "",
                  "nvidia/nemotron-3.5-embed-1b",
                  1024,
                  Duration.ofSeconds(3)));
      assertThat(embedding.enabled()).isFalse();
    });
  }

  @Test
  void anH2VariableReachesItsField() {
    context
        .withSystemProperties("H2_NVIDIA_EMBEDDING_MODEL=nvidia/other-embed")
        .run(c ->
            assertThat(c.getBean(EmbeddingProperties.class).model())
                .isEqualTo("nvidia/other-embed"));
  }

  @Test
  void zeroDimensionsStopStartup() {
    context
        .withPropertyValues("helper2.embedding.dimensions=0")
        .run(c -> {
          assertThat(c).hasFailed();
          assertThat(c.getStartupFailure())
              .rootCause()
              .isInstanceOf(BindValidationException.class)
              .hasMessageContaining("helper2.embedding.dimensions");
        });
  }

  @Test
  void toStringNeverPrintsTheKey() {
    context
        .withPropertyValues("helper2.embedding.api-key=nvapi-secret")
        .run(c -> {
          EmbeddingProperties embedding = c.getBean(EmbeddingProperties.class);
          assertThat(embedding.enabled()).isTrue();
          assertThat(embedding.toString()).doesNotContain("nvapi-secret").contains("apiKey=***");
        });
  }
}
