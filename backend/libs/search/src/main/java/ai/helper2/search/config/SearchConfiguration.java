package ai.helper2.search.config;

import ai.helper2.platform.quota.QuotaCounter;
import ai.helper2.platform.quota.QuotaKeys;
import ai.helper2.platform.resilience.Resilience;
import ai.helper2.platform.settings.RuntimeSetting;
import ai.helper2.platform.settings.RuntimeSettings;
import ai.helper2.search.DailyLimits;
import ai.helper2.search.DescribedSource;
import ai.helper2.search.DiscoveredResources;
import ai.helper2.search.LanguageDetector;
import ai.helper2.search.QueryTranslator;
import ai.helper2.search.Ranking;
import ai.helper2.search.RankingWeights;
import ai.helper2.search.RecordedOutcomes;
import ai.helper2.search.ResultObserver;
import ai.helper2.search.SearchCache;
import ai.helper2.search.SearchPolicy;
import ai.helper2.search.SearchSource;
import ai.helper2.search.SourceHealth;
import ai.helper2.search.VitalityPolicy;
import ai.helper2.search.WebFreshness;
import ai.helper2.search.WebSearchQuota;
import ai.helper2.search.cache.RedisSearchCache;
import ai.helper2.search.internal.JdbcWebResultStore;
import ai.helper2.search.usecase.ListSources;
import ai.helper2.search.usecase.SearchResources;
import ai.helper2.shared.CategoryMatcher;
import io.micrometer.context.ContextExecutorService;
import io.micrometer.context.ContextSnapshotFactory;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
class SearchConfiguration {

  @Bean(destroyMethod = "shutdownNow")
  ExecutorService searchExecutor() {
    return ContextExecutorService.wrap(
        Executors.newVirtualThreadPerTaskExecutor(), ContextSnapshotFactory.builder().build());
  }

  @Bean
  LanguageDetector languageDetector() {
    return new LanguageDetector();
  }

  @Bean
  SourceHealth sourceHealth() {
    return new SourceHealth();
  }

  @Bean
  SearchCache searchCache(StringRedisTemplate redis, ObjectMapper json, Resilience resilience) {
    return new RedisSearchCache(redis, json, resilience);
  }

  @Bean
  JdbcWebResultStore webResultStore(JdbcClient jdbc, QuotaKeys keys, ResultObserver catalog) {
    return new JdbcWebResultStore(jdbc, keys, catalog);
  }

  @Bean
  WebSearchQuota webSearchQuota(
      QuotaCounter counter, QuotaKeys keys, RuntimeSettings runtime, SearchProperties search) {
    SearchProperties.Quota quota = search.web().quota();
    return new WebSearchQuota(
        counter,
        keys,
        new DailyLimits(quota.guest().search(), quota.guest().guide()),
        runtime.declareCap(
            RuntimeSetting.SEARCH_WEB_GLOBAL_DAILY_CREDITS, quota.globalDailyCredits()));
  }

  @Bean
  SearchPolicy searchPolicy(SearchProperties search, SearchRetentionProperties retention) {
    SearchProperties.Quota quota = search.web().quota();
    return new SearchPolicy(
        new DailyLimits(quota.guest().search(), quota.guest().guide()),
        search.staleFor(),
        search.translation().ttl(),
        retention.liveWebResults());
  }

  @Bean
  ListSources listSources(
      List<DescribedSource> sources,
      SourceHealth health,
      RecordedOutcomes recorded,
      Resilience resilience,
      WebSearchQuota quota,
      DiscoveredResources discovered,
      Clock clock) {
    return new ListSources(sources, health, recorded, resilience, quota, discovered, clock);
  }

  @Bean
  Ranking ranking(VitalityPolicy vitality) {
    return new Ranking(Ranking.FUSION_K, new RankingWeights(), vitality);
  }

  @Bean
  SearchResources federatedSearch(
      List<SearchSource> sources,
      SearchCache cache,
      ResultObserver observer,
      ExecutorService searchExecutor,
      Clock clock,
      MeterRegistry registry,
      SourceHealth health,
      LanguageDetector languages,
      ObjectProvider<QueryTranslator> translator,
      Resilience resilience,
      JdbcWebResultStore webResults,
      WebSearchQuota quota,
      QuotaKeys keys,
      SearchProperties search,
      SearchRetentionProperties retention,
      CategoryMatcher categories,
      Ranking ranking,
      VitalityPolicy vitality) {
    List<SearchSource> ordered =
        sources.stream()
            .sorted(
                Comparator.comparingInt((SearchSource s) -> s.getDescriptor().getPriority())
                    .thenComparing(s -> s.getDescriptor().getId()))
            .toList();
    if (!keys.hasSecret()
        && ordered.stream()
            .anyMatch(s -> s.getDescriptor().getMetered() && s.getDescriptor().getEnabled())) {
      throw new IllegalStateException(
          "H2_QUOTA_KEY_SECRET must be set while a metered web source (live web) is enabled");
    }
    return new SearchResources(
        ordered,
        cache,
        observer,
        searchExecutor,
        clock,
        search.staleFor(),
        search.perSourceLimit(),
        new MicrometerSearchMetrics(registry, List.of(health)),
        new ai.helper2.search.QueryAnalyzer(),
        new ai.helper2.search.ResultMerger(new ai.helper2.search.QueryAnalyzer(), categories),
        ranking,
        languages,
        translator.getIfAvailable(() -> QueryTranslator.NONE),
        search.translateTimeout(),
        resilience,
        webResults,
        quota,
        new WebFreshness(
            search.web().freshTimeBound(), search.web().freshGeneral(), retention.liveWebResults()),
        vitality);
  }
}
