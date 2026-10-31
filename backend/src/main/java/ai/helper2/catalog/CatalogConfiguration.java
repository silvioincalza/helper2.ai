package ai.helper2.catalog;

import ai.helper2.catalog.internal.EmbeddingIndexer;
import ai.helper2.catalog.internal.NvidiaEmbedder;
import ai.helper2.catalog.internal.ObservationQueue;
import ai.helper2.catalog.internal.ResourceStore;
import ai.helper2.catalog.internal.ResourceStore.DataOrigin;
import ai.helper2.platform.ClusterLock;
import ai.helper2.platform.cache.RedisSharedCache;
import ai.helper2.platform.resilience.Resilience;
import ai.helper2.search.SourceDescriptor;
import ai.helper2.shared.ResourceType;
import ai.helper2.shared.SourceMode;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.observation.ObservationRegistry;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration(proxyBeanMethods = false)
class CatalogConfiguration {

    /** Background writer for what searches observed; see ResourceCatalog. */
    @Bean(destroyMethod = "close")
    ObservationQueue observationQueue(MeterRegistry registry,
                                      @Value("${helper2.catalog.observation-queue:256}") int capacity) {
        return new ObservationQueue(capacity, registry);
    }

    /** The review backlog: items waiting for a person (one count query per scrape). */
    @Bean
    MeterBinder reviewBacklogGauge(ReviewQueue queue) {
        return meters -> Gauge.builder("helper2.review.open_items", queue, ReviewQueue::openCount)
            .description("Review items waiting for a decision (status OPEN)")
            .register(meters);
    }

    @Bean
    CatalogSearchSource indexSearchSource(ResourceStore store, Clock clock,
                                          @Value("${helper2.sources.index.timeout:3s}") Duration timeout) {
        SourceDescriptor d = new SourceDescriptor(
            "helper2-index", "Helper2 index", SourceMode.INDEXED, URI.create("https://helper2.ai/sources"), null,
            "Resources collected by the Helper2 AI Resource Scout or seen in earlier searches",
            EnumSet.allOf(ResourceType.class), timeout, Duration.ZERO, true, 50, false, true, true, true);
        return new CatalogSearchSource(d, store, Set.of(DataOrigin.INDEXED, DataOrigin.OBSERVED), clock);
    }

    @Bean
    CatalogSearchSource curatedSearchSource(ResourceStore store, Clock clock,
                                            @Value("${helper2.sources.curated.enabled:true}") boolean enabled,
                                            @Value("${helper2.sources.curated.timeout:1000ms}") Duration timeout) {
        SourceDescriptor d = new SourceDescriptor(
            "curated", "Helper2 curated list", SourceMode.CURATED, URI.create("https://helper2.ai/sources"), null,
            "Editorial seed written by the Helper2 team; not live data. Dates are unknown unless sourced.",
            EnumSet.of(ResourceType.PRODUCT), timeout, Duration.ZERO, enabled, 10, false, true, true, true);
        return new CatalogSearchSource(d, store, Set.of(DataOrigin.CURATED), clock);
    }

    /**
     * Vector search (ADR 0016): on only with an embedding API key. Without one, search is keyword-only and
     * nothing below exists.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnExpression("'${helper2.embedding.api-key:}' != ''")
    static class VectorSearch {

        private static final Logger log = LoggerFactory.getLogger(VectorSearch.class);

        @Bean
        Embedder embedder(@Value("${helper2.embedding.base-url}") String baseUrl,
                          @Value("${helper2.embedding.api-key}") String apiKey,
                          @Value("${helper2.embedding.model}") String model,
                          @Value("${helper2.embedding.dimensions:1024}") int dimensions,
                          @Value("${helper2.embedding.timeout:3s}") Duration timeout,
                          ObservationRegistry observations, Resilience resilience) {
            log.info("Vector search on: model={}, dimensions={}", model, dimensions);
            return new NvidiaEmbedder(baseUrl, apiKey, model, dimensions, timeout, observations, resilience);
        }

        @Bean
        SemanticSearchSource semanticSearchSource(ResourceStore store, Embedder embedder, Clock clock,
                                                  StringRedisTemplate redis, Resilience resilience, MeterRegistry meters,
                                                  @Value("${helper2.sources.semantic.timeout:3s}") Duration timeout,
                                                  @Value("${helper2.sources.semantic.max-distance:0.65}") double maxDistance,
                                                  @Value("${helper2.search.query-vector-ttl:7d}") Duration queryVectorTtl) {
            SourceDescriptor d = new SourceDescriptor(
                "helper2-semantic", "Helper2 index (by meaning)", SourceMode.INDEXED, URI.create("https://helper2.ai/sources"), null,
                "The Helper2 index searched by meaning with " + embedder.model() + " embeddings",
                // Embeds the query as typed, so it never waits for the English translation.
                EnumSet.allOf(ResourceType.class), timeout, Duration.ZERO, true, 55, false, false);
            var queryVectors = new QueryVectorCache(
                new RedisSharedCache("qvec", queryVectorTtl, redis, resilience, meters), embedder.model(), embedder.dimensions());
            return new SemanticSearchSource(d, store, embedder, queryVectors,
                Set.of(DataOrigin.INDEXED, DataOrigin.OBSERVED, DataOrigin.CURATED), maxDistance, clock);
        }

        /**
         * Its own client with a longer timeout: a batch of 50 texts can take seconds, while a search query
         * must give up fast. With the query timeout (3 s) some staging batches timed out (27 Oct 2026).
         */
        @Bean
        EmbeddingIndexer embeddingIndexer(ResourceStore store, Embedder embedder, Clock clock, MeterRegistry registry,
                                          ObservationRegistry observations, Resilience resilience,
                                          @Value("${helper2.embedding.base-url}") String baseUrl,
                                          @Value("${helper2.embedding.api-key}") String apiKey,
                                          @Value("${helper2.embedding.index-timeout:30s}") Duration indexTimeout,
                                          @Value("${helper2.embedding.batch:50}") int batch) {
            Embedder indexing = new NvidiaEmbedder(baseUrl, apiKey, embedder.model(), embedder.dimensions(), indexTimeout, observations,
                resilience);
            return new EmbeddingIndexer(store, indexing, batch, Duration.ofMinutes(2), clock, registry);
        }

        /**
         * One batch at a time across replicas, and one per period ([every]) in total: the batch size and period
         * keep the embedding API's request rate where it was measured (4 per minute), however many replicas run.
         * At most: one embedding call can wait for a connection, connect and read, each up to the index timeout,
         * plus three short queries (with 30 s: 2 min 30 s), and never less than the least. At least: 90 % of the
         * period, so a replica whose schedule fires a little later skips that period instead of embedding a
         * second batch in it.
         */
        @Bean(initMethod = "start", destroyMethod = "stop")
        IndexerSchedule embeddingIndexerSchedule(EmbeddingIndexer indexer, LockingTaskExecutor locks,
                                                 @Value("${helper2.embedding.index-timeout:30s}") Duration indexTimeout,
                                                 @Value("${helper2.embedding.initial-delay:PT30S}") Duration initialDelay,
                                                 @Value("${helper2.embedding.every:PT15S}") Duration every) {
            Duration atLeastFor = every.multipliedBy(9).dividedBy(10);
            Duration batchWorstCase = indexTimeout.multipliedBy(3).plusMinutes(1);
            ClusterLock lock = new ClusterLock(locks, "embedding-indexer",
                batchWorstCase.compareTo(atLeastFor) > 0 ? batchWorstCase : atLeastFor, atLeastFor);
            return new IndexerSchedule(indexer, lock, initialDelay, every);
        }

        /**
         * Its own thread, not @Scheduled: with virtual threads Spring runs every fixed-delay task on one
         * scheduler thread, so a Scout run (minutes) stalled the indexer (seen on staging, 27 Oct 2026).
         * Fixed delay, so a slow batch never overlaps the next one.
         */
        static class IndexerSchedule {
            private final EmbeddingIndexer indexer;
            private final ClusterLock lock;
            private final Duration initialDelay;
            private final Duration every;
            private ScheduledExecutorService executor;

            IndexerSchedule(EmbeddingIndexer indexer, ClusterLock lock, Duration initialDelay, Duration every) {
                this.indexer = indexer;
                this.lock = lock;
                this.initialDelay = initialDelay;
                this.every = every;
            }

            void start() {
                executor = Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "embedding-indexer");
                    t.setDaemon(true);
                    return t;
                });
                executor.scheduleWithFixedDelay(this::runSafely, initialDelay.toMillis(), every.toMillis(), TimeUnit.MILLISECONDS);
            }

            void stop() {
                if (executor != null) {
                    executor.shutdownNow();
                }
            }

            /** An exception escaping a scheduled task would cancel all later runs. */
            private void runSafely() {
                try {
                    lock.tryRun(indexer::tick);
                } catch (RuntimeException e) {
                    log.warn("Embedding indexer tick failed: {}", e.getClass().getSimpleName());
                }
            }
        }
    }
}
