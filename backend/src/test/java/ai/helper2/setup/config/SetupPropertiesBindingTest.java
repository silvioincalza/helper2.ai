package ai.helper2.setup.config;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.testing.ConfigBinding;
import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.unit.DataSize;

/** The setup guide's settings bind from application.yaml, and H2_ variables reach them. */
class SetupPropertiesBindingTest {

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(SetupProperties.class)
  static class Binding {}

  final ApplicationContextRunner context = ConfigBinding.of(Binding.class);

  @Test
  void theYamlDefaultsBind() {
    context.run(c ->
        assertThat(c.getBean(SetupProperties.class))
            .isEqualTo(
                new SetupProperties(
                    Duration.ofSeconds(10),
                    URI.create("https://cdn.jsdelivr.net"),
                    Duration.ofHours(1),
                    DataSize.ofMegabytes(32),
                    1000,
                    "",
                    new SetupProperties.Hosts(
                        URI.create("https://api.github.com"),
                        URI.create("https://huggingface.co"),
                        ""),
                    new SetupProperties.McpRegistry(
                        URI.create("https://registry.modelcontextprotocol.io"),
                        Duration.ofSeconds(10)),
                    new SetupProperties.Guide(
                        300, false, Duration.ofDays(7), Duration.ofMinutes(10)))));
  }

  @Test
  void anH2VariableReachesItsField() {
    context
        .withSystemProperties(
            "H2_SETUP_HOURLY_FETCHES=5",
            "H2_SETUP_GUIDE_CACHE_TTL=PT2H",
            "H2_SETUP_GITHUB_TOKEN=x",
            "H2_SETUP_DOCUMENT_CACHE_MB=8",
            "H2_HF_TOKEN=hf_x")
        .run(c -> {
          SetupProperties setup = c.getBean(SetupProperties.class);
          assertThat(setup.hourlyFetches()).isEqualTo(5);
          assertThat(setup.guide().cacheTtl()).isEqualTo(Duration.ofHours(2));
          assertThat(setup.githubToken()).isEqualTo("x");
          assertThat(setup.documentCache())
              .as("a plain number is megabytes")
              .isEqualTo(DataSize.ofMegabytes(8));
          assertThat(setup.hosts().hfToken()).as("the sources' token").isEqualTo("hf_x");
        });
  }

  @Test
  void theHostsFollowTheSourcesKeys() {
    context
        .withPropertyValues(
            "helper2.sources.github.base-url=http://localhost:1/github",
            "helper2.sources.mcp-registry.base-url=http://localhost:1/registry")
        .run(c -> {
          SetupProperties setup = c.getBean(SetupProperties.class);
          assertThat(setup.hosts().github()).isEqualTo(URI.create("http://localhost:1/github"));
          assertThat(setup.mcpRegistry().baseUrl())
              .isEqualTo(URI.create("http://localhost:1/registry"));
        });
  }

  @Test
  void zeroHourlyFetchesStopStartup() {
    context
        .withSystemProperties("H2_SETUP_HOURLY_FETCHES=0")
        .run(c -> {
          assertThat(c).hasFailed();
          assertThat(c.getStartupFailure())
              .rootCause()
              .isInstanceOf(BindValidationException.class)
              .hasMessageContaining("hourlyFetches");
        });
  }

  @Test
  void toStringNeverPrintsATokenOrTheHfToken() {
    context
        .withSystemProperties("H2_SETUP_GITHUB_TOKEN=ghp_secret", "H2_HF_TOKEN=hf_secret")
        .run(c ->
            assertThat(c.getBean(SetupProperties.class).toString())
                .doesNotContain("ghp_secret", "hf_secret")
                .contains("githubToken=***", "hfToken=***", "hourlyFetches=1000"));
  }
}
