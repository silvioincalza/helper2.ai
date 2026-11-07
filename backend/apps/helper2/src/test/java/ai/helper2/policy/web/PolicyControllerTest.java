package ai.helper2.policy.web;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.api.generated.model.PolicyDto;
import ai.helper2.api.generated.model.WebSearchLimitsDto;
import ai.helper2.catalog.CatalogPolicy;
import ai.helper2.guide.GuidePolicy;
import ai.helper2.identity.AccountPolicy;
import ai.helper2.platform.config.CategoriesProperties;
import ai.helper2.platform.config.PlatformRetentionProperties;
import ai.helper2.scout.ScoutPolicy;
import ai.helper2.search.DailyLimits;
import ai.helper2.search.SearchPolicy;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class PolicyControllerTest {

  private static PolicyDto policy(boolean registeredTier) {
    return new PolicyController(
            new SearchPolicy(
                new DailyLimits(2, 4, 8, 16),
                registeredTier,
                Duration.ofHours(12),
                Duration.ofHours(6),
                Duration.ofDays(3)),
            new GuidePolicy(40, 25, 12, Duration.ofHours(2)),
            new AccountPolicy(Duration.ofDays(365), Duration.ofDays(730)),
            new CatalogPolicy(
                Duration.ofDays(30),
                Duration.ofDays(1),
                Duration.ofDays(180),
                Duration.ofDays(150),
                Duration.ofDays(20),
                200),
            new ScoutPolicy(Duration.ofHours(24), Duration.ofDays(30), 4, 300, 400, 2400),
            new PlatformRetentionProperties(Duration.ofDays(90), Duration.ofDays(7)),
            categories())
        .getPolicy()
        .getBody();
  }

  private static CategoriesProperties categories() {
    return new CategoriesProperties(
        List.of(
            new CategoriesProperties.Entry(
                "coding",
                "dev",
                "Coding",
                "Coding tools",
                List.of("code"),
                new CategoriesProperties.Discovery(null, null, List.of("q1", "q2"))),
            new CategoriesProperties.Entry(
                "writing",
                "work",
                "Writing",
                "Writing tools",
                List.of("write"),
                new CategoriesProperties.Discovery(null, null, List.of("q3"))),
            new CategoriesProperties.Entry(
                "health", "life", "Health", "Health tools", null, null)));
  }

  @Test
  void everyNumberComesFromItsModule() {
    PolicyDto p = policy(false);

    assertThat(p.getGuide().getMaxCandidates()).isEqualTo(12);
    assertThat(p.getGuide().getMaxPicks()).isEqualTo(3);
    assertThat(p.getDailyLimits().getGuideQuestions()).isEqualTo(40);
    assertThat(p.getDailyLimits().getFreeAnswers()).isEqualTo(25);
    assertThat(p.getDailyLimits().getWebSearches().getGuest())
        .isEqualTo(new WebSearchLimitsDto(2, 4));
    assertThat(p.getRetention().getCachedSearchesSeconds()).isEqualTo(43_200);
    assertThat(p.getRetention().getCachedGuideAnswersSeconds()).isEqualTo(7_200);
    assertThat(p.getRetention().getTranslationsSeconds()).isEqualTo(21_600);
    assertThat(p.getRetention().getQueryVectorsSeconds()).isEqualTo(86_400);
    assertThat(p.getRetention().getStoredWebResultsSeconds()).isEqualTo(259_200);
    assertThat(p.getRetention().getInactiveAccountsSeconds()).isEqualTo(31_536_000);
    assertThat(p.getRetention().getHandledReportsSeconds()).isEqualTo(2_592_000);
    assertThat(p.getRetention().getAdminRecordsSeconds()).isEqualTo(63_072_000);
    assertThat(p.getRetention().getRuntimeSettingChangesSeconds()).isEqualTo(7_776_000);
  }

  @Test
  void theNumbersThePagesUsedToTypeComeFromCodeAndConfiguration() {
    PolicyDto p = policy(false);

    assertThat(p.getRetention().getWebSearchCountersSeconds()).isEqualTo(86_400);
    assertThat(p.getRetention().getGuideQuestionCountersSeconds()).isEqualTo(172_800);
    assertThat(p.getRetention().getWebNewsSeconds()).isEqualTo(15_552_000);
    assertThat(p.getRetention().getPendingReviewPagesSeconds()).isEqualTo(12_960_000);
    assertThat(p.getRetention().getListIndexSeconds()).isEqualTo(1_728_000);
    assertThat(p.getRetention().getPageTextSeconds()).isEqualTo(86_400);
    assertThat(p.getRetention().getPageReadSeconds()).isEqualTo(2_592_000);
    assertThat(p.getRetention().getBackupsSeconds()).isEqualTo(604_800);
    assertThat(p.getSources().getCategories()).isEqualTo(3);
    assertThat(p.getSources().getCategorySearches()).isEqualTo(3);
    assertThat(p.getSources().getQuoteChars()).isEqualTo(300);
    assertThat(p.getSources().getArticleExcerptChars()).isEqualTo(400);
    assertThat(p.getSources().getProductPageExcerptChars()).isEqualTo(2400);
    assertThat(p.getSources().getProductPages()).isEqualTo(4);
    assertThat(p.getSources().getErrorLogChars()).isEqualTo(200);
  }

  @Test
  void signedInLimitsOnlyWithTheRegisteredTier() {
    assertThat(policy(false).getDailyLimits().getWebSearches().getRegistered()).isNull();
    assertThat(policy(true).getDailyLimits().getWebSearches().getRegistered())
        .isEqualTo(new WebSearchLimitsDto(8, 16));
  }
}
