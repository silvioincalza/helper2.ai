package ai.searchavo.scout.config;

import static org.assertj.core.api.Assertions.assertThat;

import ai.searchavo.platform.llm.ModelTier;
import ai.searchavo.testing.ConfigBinding;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class ScoutPropertiesBindingTest {

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties({
    ScoutProperties.class,
    ScoutRetentionProperties.class,
    ScoutAdmissionProperties.class
  })
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
                  Duration.ofMinutes(10),
                  "0 7 3 * * *",
                  1000,
                  20,
                  500,
                  400,
                  8192,
                  ModelTier.FAST,
                  new ScoutProperties.Evidence(1000),
                  new ScoutProperties.KnownTargets(5, 25),
                  new ScoutProperties.RepositorySweep(
                      true, Duration.ofMinutes(10), Duration.ofMinutes(2), 50, 3)));
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
            "SA_SCOUT_ENABLED=true",
            "SA_SCOUT_CRON=0 30 4 * * *",
            "SA_SCOUT_MAX_ENRICHMENTS=5",
            "SA_NEBIUS_ENRICH_MAX_TOKENS=4096",
            "SA_SCOUT_ENRICHMENT_MODEL=REASONING",
            "SA_SCOUT_PAGE_READER_DAILY_CREDITS=7",
            "SA_SCOUT_KNOWN_TARGETS_OWNER_MIN_RESOURCES=8",
            "SA_SCOUT_KNOWN_TARGETS_PER_DAY=0",
            "SA_RETENTION_SCOUT_RUNS=P30D")
        .run(c -> {
          ScoutProperties scout = c.getBean(ScoutProperties.class);
          assertThat(scout.enabled()).isTrue();
          assertThat(scout.evidence().dailyPageReadCredits()).isEqualTo(7);
          assertThat(scout.knownTargets()).isEqualTo(new ScoutProperties.KnownTargets(8, 0));
          assertThat(scout.cron()).isEqualTo("0 30 4 * * *");
          assertThat(scout.maxEnrichmentsPerRun()).isEqualTo(5);
          assertThat(scout.enrichMaxTokens()).isEqualTo(4096);
          assertThat(scout.enrichmentModel()).isEqualTo(ModelTier.REASONING);
          assertThat(c.getBean(ScoutRetentionProperties.class).scoutRuns())
              .isEqualTo(Duration.ofDays(30));
        });
  }

  @Test
  void theAdmissionNumbersOfEveryCollectorBindInOnePlace() {
    context.run(c ->
        assertThat(c.getBean(ScoutAdmissionProperties.class))
            .isEqualTo(
                new ScoutAdmissionProperties(
                    new ScoutAdmissionProperties.GitHubTopicLeaders(1000, Duration.ofDays(90)),
                    new ScoutAdmissionProperties.HfTrending(20))));
  }

  @Test
  void theTopicLeaderVariablesKeepTheirNamesAndReachTheAdmissionNumbers() {
    context
        .withSystemProperties(
            "SA_SCOUT_TOPIC_LEADERS_INDEX_MIN_STARS=3000",
            "SA_SCOUT_TOPIC_LEADERS_INDEX_PUSHED_WITHIN=P180D")
        .run(c ->
            assertThat(c.getBean(ScoutAdmissionProperties.class).githubTopicLeaders())
                .isEqualTo(
                    new ScoutAdmissionProperties.GitHubTopicLeaders(3000, Duration.ofDays(180))));
  }

  @Test
  void anUnreadableCronStopsStartup() {
    context
        .withSystemProperties("SA_SCOUT_CRON=every day")
        .run(c -> {
          assertThat(c).hasFailed();
          assertThat(c.getStartupFailure()).rootCause().hasMessageContaining("cron");
        });
  }

  @Test
  void aNegativeBudgetStopsStartup() {
    context
        .withSystemProperties("SA_SCOUT_DAILY_ENRICHMENT_CALLS=-1")
        .run(c -> {
          assertThat(c).hasFailed();
          assertThat(c.getStartupFailure())
              .rootCause()
              .isInstanceOf(BindValidationException.class)
              .hasMessageContaining("dailyEnrichmentCalls");
        });
  }
}
