package ai.helper2.sources.config;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.sources.config.SourcesConfiguration.SourcesProperties;
import ai.helper2.sources.support.SourceSettings;
import ai.helper2.testing.ConfigBinding;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/** Every source's settings bind from application.yaml, and H2_SOURCE_* variables reach them. */
class SourcesPropertiesBindingTest {

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(SourcesProperties.class)
  static class Binding {}

  final ApplicationContextRunner context = ConfigBinding.of(Binding.class);

  @Test
  void theYamlDefaultsBind() {
    context.run(c -> {
      SourcesProperties sources = c.getBean(SourcesProperties.class);
      assertThat(sources.get("github"))
          .isEqualTo(
              new SourceSettings(
                  true,
                  "https://api.github.com",
                  "",
                  Duration.ofMillis(2500),
                  Duration.ofMinutes(30),
                  Duration.ofMillis(2500).multipliedBy(2)));
      assertThat(sources.get("arxiv").cacheTtl()).isEqualTo(Duration.ofHours(6));
      assertThat(sources.get("tavily").timeout()).isEqualTo(Duration.ofSeconds(6));
      assertThat(sources.get("tavily").cacheTtl()).isEqualTo(Duration.ofMinutes(30));
      assertThat(sources.get("hf-trending").deadline())
          .as("empty deadline: twice the timeout")
          .isEqualTo(Duration.ofSeconds(20));
      for (String collector :
          new String[] {
            "hf-trending",
            "tavily-scout",
            "course-scout",
            "discovery",
            "github-awesome",
            "hf-provider-orgs",
            "ai-deadlines",
            "mcp-registry"
          }) {
        assertThat(sources.get(collector).cacheTtl()).isEqualTo(Duration.ofMinutes(30));
      }
    });
  }

  @Test
  void anH2VariableReachesEveryKeyThatNamesIt() {
    context
        .withSystemProperties(
            "H2_GITHUB_TOKEN=ghp_x",
            "H2_SOURCE_TAVILY_TIMEOUT=8s",
            "H2_SOURCE_MCP_DEADLINE=PT45S",
            "H2_SOURCE_NPM_ENABLED=false")
        .run(c -> {
          SourcesProperties sources = c.getBean(SourcesProperties.class);
          assertThat(sources.get("github").token()).isEqualTo("ghp_x");
          assertThat(sources.get("github-awesome").token()).isEqualTo("ghp_x");
          assertThat(sources.get("ai-deadlines").token()).isEqualTo("ghp_x");
          assertThat(sources.get("tavily").timeout()).isEqualTo(Duration.ofSeconds(8));
          assertThat(sources.get("mcp-registry").deadline()).isEqualTo(Duration.ofSeconds(45));
          assertThat(sources.get("npm").enabled()).isFalse();
        });
  }

  @Test
  void anEmptyTimeoutStopsStartupAndNamesTheProperty() {
    context
        .withPropertyValues("H2_SOURCE_TAVILY_TIMEOUT=")
        .run(c -> {
          assertThat(c).hasFailed();
          assertThat(c.getStartupFailure())
              .rootCause()
              .isInstanceOf(BindValidationException.class)
              .hasMessageContaining("helper2.sources[tavily].timeout");
        });
  }

  @Test
  void toStringNeverPrintsAToken() {
    context
        .withSystemProperties("H2_HF_TOKEN=hf_secret")
        .run(c ->
            assertThat(c.getBean(SourcesProperties.class).toString())
                .doesNotContain("hf_secret")
                .contains("token=***"));
  }
}
