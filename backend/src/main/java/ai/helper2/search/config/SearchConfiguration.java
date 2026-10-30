package ai.helper2.search.config;

import ai.helper2.platform.resilience.Resilience;
import ai.helper2.search.usecase.SearchResources;
import ai.helper2.search.QueryTranslator;
import ai.helper2.search.ResultObserver;
import ai.helper2.search.SearchCache;
import ai.helper2.search.SearchSource;
import ai.helper2.search.SourceHealth;
import ai.helper2.search.cache.RedisSearchCache;
import ai.helper2.shared.LanguageDetector;
import io.micrometer.context.ContextExecutorService;
import io.micrometer.context.ContextSnapshotFactory;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
class SearchConfiguration {

    @Bean(destroyMethod = "shutdownNow")
    ExecutorService searchExecutor() {
        // Wrapped so the caller's trace/observation context follows each task into its virtual thread.
        return ContextExecutorService.wrap(Executors.newVirtualThreadPerTaskExecutor(), ContextSnapshotFactory.builder().build());
    }

    /** Language of queries (search) and of catalog text (catalog ingest); models load once at startup. */
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
    SearchResources federatedSearch(List<SearchSource> sources, SearchCache cache, ResultObserver observer,
                                    ExecutorService searchExecutor, Clock clock, MeterRegistry registry,
                                    SourceHealth health, LanguageDetector languages,
                                    ObjectProvider<QueryTranslator> translator, Resilience resilience,
                                    @Value("${helper2.search.stale-for:24h}") Duration staleFor,
                                    @Value("${helper2.search.per-source-limit:30}") int perSourceLimit,
                                    @Value("${helper2.search.translate-timeout-ms:2500}") long translateTimeoutMillis) {
        List<SearchSource> ordered = sources.stream()
            .sorted(Comparator.comparingInt((SearchSource s) -> s.getDescriptor().getPriority())
                .thenComparing(s -> s.getDescriptor().getId()))
            .toList();
        return new SearchResources(ordered, cache, observer, searchExecutor, clock, staleFor, perSourceLimit,
            new MicrometerSearchMetrics(registry, List.of(health)),
            new ai.helper2.search.QueryAnalyzer(), new ai.helper2.search.ResultMerger(), new ai.helper2.search.Ranking(60),
            languages, translator.getIfAvailable(() -> QueryTranslator.NONE), Duration.ofMillis(translateTimeoutMillis), resilience);
    }
}
