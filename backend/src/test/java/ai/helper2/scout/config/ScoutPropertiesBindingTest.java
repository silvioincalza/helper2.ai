package ai.helper2.scout.config;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.testing.ConfigBinding;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/** The Scout's schedule and budgets bind from application.yaml, and H2_ variables reach them. */
class ScoutPropertiesBindingTest {

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties({ScoutProperties.class, ScoutRetentionProperties.class})
  static class Binding {}

  final ApplicationContextRunner context = ConfigBinding.of(Binding.class);

  @Test
  void theYamlDefaultsBind() {
    context.run(c -> {
      assertThat(c.getBean(ScoutProperties.class))
          .isEqualTo(
              new ScoutProperties(
                  false,
                  Duration.ofSeconds(20),
                  Duration.ofHours(1),
                  Duration.ofMinutes(15),
                  500,
                  20,
                  500,
                  400,
                  8192));
      assertThat(c.getBean(ScoutRetentionProperties.class))
          .isEqualTo(new ScoutRetentionProperties(Duration.ofDays(90)));
    });
  }

  @Test
  void theEnrichmentCapIsTheSameWithTheNebiusProfile() {
    context
        .withPropertyValues("spring.profiles.active=llm-nebius")
        .run(c -> assertThat(c.getBean(ScoutProperties.class).enrichMaxTokens()).isEqualTo(8192));
  }

  @Test
  void anH2VariableReachesItsField() {
    context
        .withSystemProperties(
            "H2_SCOUT_ENABLED=true",
            "H2_SCOUT_EVERY=PT6H",
            "H2_SCOUT_MAX_ENRICHMENTS=5",
            "H2_NEBIUS_ENRICH_MAX_TOKENS=4096",
            "H2_RETENTION_SCOUT_RUNS=P30D")
        .run(c -> {
          ScoutProperties scout = c.getBean(ScoutProperties.class);
          assertThat(scout.enabled()).isTrue();
          assertThat(scout.every()).isEqualTo(Duration.ofHours(6));
          assertThat(scout.maxEnrichmentsPerRun()).isEqualTo(5);
          assertThat(scout.enrichMaxTokens()).isEqualTo(4096);
          assertThat(c.getBean(ScoutRetentionProperties.class).scoutRuns())
              .isEqualTo(Duration.ofDays(30));
        });
  }

  @Test
  void aNegativeBudgetStopsStartup() {
    context
        .withSystemProperties("H2_SCOUT_DAILY_ENRICHMENT_CALLS=-1")
        .run(c -> {
          assertThat(c).hasFailed();
          assertThat(c.getStartupFailure())
              .rootCause()
              .isInstanceOf(BindValidationException.class)
              .hasMessageContaining("dailyEnrichmentCalls");
        });
  }
}
