package ai.searchavo.setup.config;

import static org.assertj.core.api.Assertions.assertThat;

import ai.searchavo.setup.StepTextMode;
import ai.searchavo.testing.ConfigBinding;
import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.unit.DataSize;

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
                        500,
                        20,
                        200,
                        false,
                        Duration.ofDays(7),
                        Duration.ofMinutes(10),
                        StepTextMode.VERIFIED))));
  }

  @Test
  void anH2VariableReachesItsField() {
    context
        .withSystemProperties(
            "SA_SETUP_HOURLY_FETCHES=5",
            "SA_SETUP_GUIDE_CACHE_TTL=PT2H",
            "SA_SETUP_GITHUB_TOKEN=x",
            "SA_SETUP_DOCUMENT_CACHE_MB=8",
            "SA_HF_TOKEN=hf_x")
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
  void theStepTextModeIsAnEnumSetByItsH2Variable() {
    context
        .withSystemProperties("SA_SETUP_GUIDE_STEP_TEXT=SOURCE_ONLY")
        .run(c ->
            assertThat(c.getBean(SetupProperties.class).guide().stepText())
                .isEqualTo(StepTextMode.SOURCE_ONLY));
  }

  @Test
  void anUnknownStepTextModeStopsStartup() {
    context
        .withSystemProperties("SA_SETUP_GUIDE_STEP_TEXT=SOMETIMES")
        .run(c -> assertThat(c).hasFailed());
  }

  @Test
  void theHostsFollowTheSourcesKeys() {
    context
        .withPropertyValues(
            "searchavo.sources.github.base-url=http://localhost:1/github",
            "searchavo.sources.mcp-registry.base-url=http://localhost:1/registry")
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
        .withSystemProperties("SA_SETUP_HOURLY_FETCHES=0")
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
        .withSystemProperties("SA_SETUP_GITHUB_TOKEN=ghp_secret", "SA_HF_TOKEN=hf_secret")
        .run(c ->
            assertThat(c.getBean(SetupProperties.class).toString())
                .doesNotContain("ghp_secret", "hf_secret")
                .contains("githubToken=***", "hfToken=***", "hourlyFetches=1000"));
  }
}
