package ai.helper2.policy.web;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.api.generated.model.PolicyDto;
import ai.helper2.api.generated.model.WebSearchLimitsDto;
import ai.helper2.catalog.CatalogPolicy;
import ai.helper2.guide.GuidePolicy;
import ai.helper2.identity.AccountPolicy;
import ai.helper2.search.DailyLimits;
import ai.helper2.search.SearchPolicy;
import java.time.Duration;
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
            new GuidePolicy(40, 12, Duration.ofHours(2)),
            new AccountPolicy(Duration.ofDays(365)),
            new CatalogPolicy(Duration.ofDays(30), Duration.ofDays(1)))
        .getPolicy()
        .getBody();
  }

  @Test
  void everyNumberComesFromItsModule() {
    PolicyDto p = policy(false);

    assertThat(p.getGuide().getMaxCandidates()).isEqualTo(12);
    assertThat(p.getGuide().getMaxPicks()).isEqualTo(3);
    assertThat(p.getDailyLimits().getGuideQuestions()).isEqualTo(40);
    assertThat(p.getDailyLimits().getWebSearches().getGuest())
        .isEqualTo(new WebSearchLimitsDto(2, 4));
    assertThat(p.getRetention().getCachedSearchesSeconds()).isEqualTo(43_200);
    assertThat(p.getRetention().getCachedGuideAnswersSeconds()).isEqualTo(7_200);
    assertThat(p.getRetention().getTranslationsSeconds()).isEqualTo(21_600);
    assertThat(p.getRetention().getQueryVectorsSeconds()).isEqualTo(86_400);
    assertThat(p.getRetention().getStoredWebResultsSeconds()).isEqualTo(259_200);
    assertThat(p.getRetention().getInactiveAccountsSeconds()).isEqualTo(31_536_000);
    assertThat(p.getRetention().getHandledReportsSeconds()).isEqualTo(2_592_000);
  }

  @Test
  void signedInLimitsOnlyWithTheRegisteredTier() {
    assertThat(policy(false).getDailyLimits().getWebSearches().getRegistered()).isNull();
    assertThat(policy(true).getDailyLimits().getWebSearches().getRegistered())
        .isEqualTo(new WebSearchLimitsDto(8, 16));
  }
}
