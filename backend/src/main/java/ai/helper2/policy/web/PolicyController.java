package ai.helper2.policy.web;

import ai.helper2.api.generated.PolicyApi;
import ai.helper2.api.generated.model.PolicyDailyLimitsDto;
import ai.helper2.api.generated.model.PolicyDailyLimitsWebSearchesDto;
import ai.helper2.api.generated.model.PolicyDto;
import ai.helper2.api.generated.model.PolicyGuideDto;
import ai.helper2.api.generated.model.PolicyRetentionDto;
import ai.helper2.api.generated.model.WebSearchLimitsDto;
import ai.helper2.catalog.CatalogPolicy;
import ai.helper2.guide.GuidePolicy;
import ai.helper2.identity.AccountPolicy;
import ai.helper2.search.DailyLimits;
import ai.helper2.search.SearchPolicy;
import java.time.Duration;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The numbers the text pages state, from the policy value of each owning module. The same answer
 * for everyone, with no personal data; it changes only with a restart, so it is cached.
 */
@RestController
@RequestMapping("/api/v1")
class PolicyController implements PolicyApi {

  private static final CacheControl CACHE =
      CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic();

  private final PolicyDto policy;

  PolicyController(
      SearchPolicy search, GuidePolicy guide, AccountPolicy accounts, CatalogPolicy catalog) {
    this.policy =
        new PolicyDto(
            new PolicyGuideDto(guide.getMaxCandidates(), guide.getMaxPicks()),
            dailyLimits(search, guide),
            retention(search, guide, accounts, catalog));
  }

  @Override
  public ResponseEntity<PolicyDto> getPolicy() {
    return ResponseEntity.ok().cacheControl(CACHE).body(policy);
  }

  private static PolicyDailyLimitsDto dailyLimits(SearchPolicy search, GuidePolicy guide) {
    DailyLimits web = search.getWebSearches();
    WebSearchLimitsDto guest = new WebSearchLimitsDto(web.getGuestSearch(), web.getGuestGuide());
    WebSearchLimitsDto registered =
        search.getRegisteredTier()
            ? new WebSearchLimitsDto(web.getRegisteredSearch(), web.getRegisteredGuide())
            : null;
    return new PolicyDailyLimitsDto(
        guide.getQuestionsPerDay(), new PolicyDailyLimitsWebSearchesDto(guest, registered));
  }

  private static PolicyRetentionDto retention(
      SearchPolicy search, GuidePolicy guide, AccountPolicy accounts, CatalogPolicy catalog) {
    return new PolicyRetentionDto(
        search.getCachedSearches().toSeconds(),
        guide.getCachedAnswers().toSeconds(),
        search.getTranslations().toSeconds(),
        catalog.queryVectors().toSeconds(),
        search.getStoredWebResults().toSeconds(),
        accounts.inactiveAccounts().toSeconds(),
        catalog.handledReports().toSeconds());
  }
}
