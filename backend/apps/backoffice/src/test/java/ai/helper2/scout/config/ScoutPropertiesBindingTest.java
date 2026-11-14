package ai.helper2.scout.config;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.testing.ConfigBinding;
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
                  new ScoutProperties.Evidence(20, Duration.ofDays(30), Duration.ofHours(24)),
                  new ScoutProperties.KnownTargets(5, 25)));
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
            "H2_SCOUT_CRON=0 30 4 * * *",
            "H2_SCOUT_MAX_ENRICHMENTS=5",
            "H2_NEBIUS_ENRICH_MAX_TOKENS=4096",
            "H2_SCOUT_PAGE_READER_DAILY_CREDITS=7",
            "H2_SCOUT_KNOWN_TARGETS_OWNER_MIN_RESOURCES=8",
            "H2_SCOUT_KNOWN_TARGETS_PER_DAY=0",
            "H2_RETENTION_SCOUT_RUNS=P30D")
        .run(c -> {
          ScoutProperties scout = c.getBean(ScoutProperties.class);
          assertThat(scout.enabled()).isTrue();
          assertThat(scout.evidence().dailyPageReadCredits()).isEqualTo(7);
          assertThat(scout.knownTargets()).isEqualTo(new ScoutProperties.KnownTargets(8, 0));
          assertThat(scout.cron()).isEqualTo("0 30 4 * * *");
          assertThat(scout.maxEnrichmentsPerRun()).isEqualTo(5);
          assertThat(scout.enrichMaxTokens()).isEqualTo(4096);
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
            "H2_SCOUT_TOPIC_LEADERS_INDEX_MIN_STARS=3000",
            "H2_SCOUT_TOPIC_LEADERS_INDEX_PUSHED_WITHIN=P180D")
        .run(c ->
            assertThat(c.getBean(ScoutAdmissionProperties.class).githubTopicLeaders())
                .isEqualTo(
                    new ScoutAdmissionProperties.GitHubTopicLeaders(3000, Duration.ofDays(180))));
  }

  @Test
  void anUnreadableCronStopsStartup() {
    context
        .withSystemProperties("H2_SCOUT_CRON=every day")
        .run(c -> {
          assertThat(c).hasFailed();
          assertThat(c.getStartupFailure()).rootCause().hasMessageContaining("cron");
        });
  }

  @Test
  void aPageTextTtlOverADayStopsStartup() {
    context
        .withSystemProperties("H2_SCOUT_EVIDENCE_PAGE_TEXT_TTL=P2D")
        .run(c -> {
          assertThat(c).hasFailed();
          assertThat(c.getStartupFailure())
              .rootCause()
              .isInstanceOf(BindValidationException.class)
              .hasMessageContaining("pageTextTtl");
        });
  }

  @Test
  void aPageTextTtlOfADayStarts() {
    context
        .withSystemProperties("H2_SCOUT_EVIDENCE_PAGE_TEXT_TTL=P1D")
        .run(c ->
            assertThat(c.getBean(ScoutProperties.class).evidence().pageTextTtl())
                .isEqualTo(Duration.ofDays(1)));
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
