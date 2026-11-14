package ai.helper2.site.policy.web;

import ai.helper2.api.PublicCache;
import ai.helper2.api.generated.PolicyApi;
import ai.helper2.api.generated.model.PolicyDailyLimitsDto;
import ai.helper2.api.generated.model.PolicyDto;
import ai.helper2.api.generated.model.PolicyGuideDto;
import ai.helper2.api.generated.model.PolicyRetentionDto;
import ai.helper2.api.generated.model.PolicySourcesDto;
import ai.helper2.api.generated.model.WebSearchLimitsDto;
import ai.helper2.catalog.CatalogPolicy;
import ai.helper2.catalog.ScoutPolicy;
import ai.helper2.guide.GuidePolicy;
import ai.helper2.identity.AccountPolicy;
import ai.helper2.platform.config.CategoriesProperties;
import ai.helper2.platform.config.PlatformRetentionProperties;
import ai.helper2.platform.llm.RedisCallCounter;
import ai.helper2.platform.quota.RedisQuotaCounter;
import ai.helper2.search.DailyLimits;
import ai.helper2.search.SearchPolicy;
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
        RedisCallCounter.KEEP.toSeconds(),
        catalog.webNews().toSeconds(),
        catalog.pendingReviewPages().toSeconds(),
        catalog.listIndex().toSeconds(),
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
        platform.runtimeSettings().toSeconds());
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
