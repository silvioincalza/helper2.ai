package ai.searchavo.site.policy.web;

import ai.searchavo.api.PublicCache;
import ai.searchavo.api.generated.PolicyApi;
import ai.searchavo.api.generated.model.PolicyDailyLimitsDto;
import ai.searchavo.api.generated.model.PolicyDto;
import ai.searchavo.api.generated.model.PolicyGuideDto;
import ai.searchavo.api.generated.model.PolicyRetentionDto;
import ai.searchavo.api.generated.model.PolicySourcesDto;
import ai.searchavo.api.generated.model.WebSearchLimitsDto;
import ai.searchavo.catalog.CatalogPolicy;
import ai.searchavo.catalog.ScoutPolicy;
import ai.searchavo.guide.GuidePolicy;
import ai.searchavo.identity.AccountPolicy;
import ai.searchavo.platform.config.CategoriesProperties;
import ai.searchavo.platform.config.PlatformRetentionProperties;
import ai.searchavo.platform.llm.BudgetLedger;
import ai.searchavo.platform.quota.RedisQuotaCounter;
import ai.searchavo.search.DailyLimits;
import ai.searchavo.search.SearchPolicy;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
class PolicyController implements PolicyApi {

  private static final CacheControl CACHE = PublicCache.SHORT;

  private final PolicyDto policy;

  PolicyController(
      SearchPolicy search,
      GuidePolicy guide,
      AccountPolicy accounts,
      CatalogPolicy catalog,
      ScoutPolicy scout,
      PlatformRetentionProperties platform,
      CategoriesProperties categories) {
    this.policy =
        new PolicyDto(
            new PolicyGuideDto(guide.getMaxCandidates(), guide.getMaxPicks()),
            dailyLimits(search, guide),
            retention(search, guide, accounts, catalog, scout, platform),
            sources(scout, catalog, categories));
  }

  @Override
  public ResponseEntity<PolicyDto> getPolicy() {
    return ResponseEntity.ok().cacheControl(CACHE).body(policy);
  }

  private static PolicyDailyLimitsDto dailyLimits(SearchPolicy search, GuidePolicy guide) {
    DailyLimits web = search.getWebSearches();
    return new PolicyDailyLimitsDto(
        guide.getQuestionsPerDay(),
        guide.getFreeAnswers(),
        new WebSearchLimitsDto(web.getSearch(), web.getGuide()));
  }

  private static PolicyRetentionDto retention(
      SearchPolicy search,
      GuidePolicy guide,
      AccountPolicy accounts,
      CatalogPolicy catalog,
      ScoutPolicy scout,
      PlatformRetentionProperties platform) {
    return new PolicyRetentionDto(
        RedisQuotaCounter.KEEP.toSeconds(),
        BudgetLedger.KEEP.toSeconds(),
        catalog.webNews().toSeconds(),
        catalog.pendingReviewPages().toSeconds(),
        scout.pageText().toSeconds(),
        scout.pageRead().toSeconds(),
        platform.backups().toSeconds(),
        search.getCachedSearches().toSeconds(),
        guide.getCachedAnswers().toSeconds(),
        search.getTranslations().toSeconds(),
        catalog.queryVectors().toSeconds(),
        search.getStoredWebResults().toSeconds(),
        accounts.inactiveAccounts().toSeconds(),
        catalog.handledReports().toSeconds(),
        accounts.adminRecords().toSeconds(),
        platform.runtimeSettings().toSeconds(),
        catalog.pastEvents().toSeconds());
  }

  private static PolicySourcesDto sources(
      ScoutPolicy scout, CatalogPolicy catalog, CategoriesProperties categories) {
    return new PolicySourcesDto(
        categories.catalog().getAll().size(),
        categories.discoverySearches(),
        scout.quoteChars(),
        scout.articleExcerptChars(),
        scout.productPageExcerptChars(),
        scout.productPages(),
        catalog.errorLogChars());
  }
}
