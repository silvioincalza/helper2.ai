package ai.helper2.catalog.internal;

import ai.helper2.catalog.CatalogPolicy;
import ai.helper2.catalog.Embedder;
import ai.helper2.catalog.ReviewQueue;
import ai.helper2.catalog.ScoutPolicy;
import ai.helper2.catalog.config.CatalogProperties;
import ai.helper2.catalog.config.CatalogRetentionProperties;
import ai.helper2.catalog.config.ScoutEvidenceProperties;
import ai.helper2.catalog.internal.ResourceStore.DataOrigin;
import ai.helper2.platform.cache.SharedCaches;
import ai.helper2.platform.config.EmbeddingProperties;
import ai.helper2.platform.llm.LlmPolicies;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ScoutEvidenceProperties.class)
class CatalogConfiguration {

  /** The Helper2 index searched by words: it serves the whole index. */
  static final String INDEX_SOURCE = "helper2-index";

  /** The same index searched by meaning. */
  static final String SEMANTIC_SOURCE = "helper2-semantic";

  /** The catalog's numbers for GET /api/v1/policy (ADR 0029). */
  @Bean
  CatalogPolicy catalogPolicy(CatalogProperties catalog, CatalogRetentionProperties retention) {
    return new CatalogPolicy(
        retention.resolvedIssueReports(),
        catalog.semantic().queryVectorTtl(),
        retention.webNews(),
        retention.resolvedReviewItems(),
        retention.listIndex(),
        NvidiaEmbedder.MAX_REASON);
  }

  /** The Scout's numbers the Privacy page states (ADR 0029). */
  @Bean
  ScoutPolicy scoutPolicy(ScoutEvidenceProperties evidence) {
    return ScoutPolicy.of(evidence.pageTextTtl(), evidence.pageReadTtl());
  }

  /** Background writer for what searches observed; see CatalogResultObserver. */
  @Bean(destroyMethod = "close")
  ObservationQueue observationQueue(MeterRegistry registry, CatalogProperties catalog) {
    return new ObservationQueue(catalog.observationQueue(), registry);
  }

  /** The review backlog: items waiting for a person (one count query per scrape). */
  @Bean
  MeterBinder reviewBacklogGauge(ReviewQueue queue) {
    return meters ->
        Gauge.builder("helper2.review.open_items", queue, ReviewQueue::openCount)
            .description("Review items waiting for a decision (status OPEN)")
            .register(meters);
  }

  @Bean
  CatalogSearchSource indexSearchSource(
      ResourceStore store, Clock clock, CatalogProperties catalog) {
    Duration timeout = catalog.index().timeout();
    boolean enabled = true;
    int priority = 50;
    boolean usesWebQuery = false;
    boolean usesTranslation = true;
    boolean filtersByDate = true;
    boolean browses = true;
    SourceDescriptor d =
        new SourceDescriptor(
            INDEX_SOURCE,
            "Helper2.ai catalog",
            "Catalog",
            false,
            SourceMode.INDEXED,
            URI.create("https://helper2.ai/sources"),
            null,
            "Resources collected by the Helper2 AI Resource Scout or seen in earlier searches",
            EnumSet.allOf(ResourceType.class),
            timeout,
            Duration.ZERO,
            enabled,
            priority,
            usesWebQuery,
            usesTranslation,
            filtersByDate,
            browses);
    return new CatalogSearchSource(
        d, store, Set.of(DataOrigin.INDEXED, DataOrigin.OBSERVED), clock);
  }

  @Bean
  CatalogSearchSource curatedSearchSource(
      ResourceStore store, Clock clock, CatalogProperties catalog) {
    boolean enabled = catalog.curated().enabled();
    Duration timeout = catalog.curated().timeout();
    int priority = 10;
    boolean usesWebQuery = false;
    boolean usesTranslation = true;
    boolean filtersByDate = true;
    boolean browses = true;
    SourceDescriptor d =
        new SourceDescriptor(
            "curated",
            "Hand-picked list",
            "Curated",
            false,
            SourceMode.CURATED,
            URI.create("https://helper2.ai/sources"),
            null,
            "Editorial seed written by the Helper2 team; not live data. Dates are unknown unless sourced.",
            // The seed is products; an admin adds models and other tools from the review list (ADR
            // 0026).
            EnumSet.copyOf(CuratedTypes.TYPES),
            timeout,
            Duration.ZERO,
            enabled,
            priority,
            usesWebQuery,
            usesTranslation,
            filtersByDate,
            browses);
    return new CatalogSearchSource(d, store, Set.of(DataOrigin.CURATED), clock);
  }

  /**
   * Vector search (ADR 0016): on only with an embedding API key. Without one, search is
   * keyword-only and nothing below exists.
   */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnExpression("'${helper2.embedding.api-key:}' != ''")
  static class VectorSearch {

    private static final Logger log = LoggerFactory.getLogger(VectorSearch.class);

    @Bean
    Embedder embedder(
        EmbeddingProperties embedding, ObservationRegistry observations, Resilience resilience) {
      log.info(
          "Vector search on: model={}, dimensions={}", embedding.model(), embedding.dimensions());
      return new NvidiaEmbedder(
          embedding.baseUrl().toString(),
          embedding.apiKey(),
          embedding.model(),
          embedding.dimensions(),
          embedding.timeout(),
          observations,
          resilience,
          LlmPolicies.EMBED);
    }

    @Bean
    SemanticSearchSource semanticSearchSource(
        ResourceStore store,
        Embedder embedder,
        Clock clock,
        SharedCaches caches,
        CatalogProperties catalog) {
      CatalogProperties.Semantic semantic = catalog.semantic();
      Duration timeout = semantic.timeout();
      boolean enabled = true;
      int priority = 55;
      boolean usesWebQuery = false;
      // Embeds the query as typed, so it never waits for the English translation.
      boolean usesTranslation = false;
      SourceDescriptor d =
          new SourceDescriptor(
              SEMANTIC_SOURCE,
              "Helper2.ai catalog, by meaning",
              "Meaning",
              false,
              SourceMode.INDEXED,
              URI.create("https://helper2.ai/sources"),
              null,
              "The Helper2 index searched by meaning with " + embedder.model() + " embeddings",
              EnumSet.allOf(ResourceType.class),
              timeout,
              Duration.ZERO,
              enabled,
              priority,
              usesWebQuery,
              usesTranslation);
      var queryVectors =
          new QueryVectorCache(
              caches.named("query-vector"),
              embedder.model(),
              embedder.dimensions(),
              semantic.queryVectorTtl());
      return new SemanticSearchSource(
          d,
          store,
          embedder,
          queryVectors,
          Set.of(DataOrigin.INDEXED, DataOrigin.OBSERVED, DataOrigin.CURATED),
          semantic.maxDistance(),
          clock);
    }

    /**
     * Its own client with a longer timeout: a batch of 50 texts can take seconds, while a search
     * query must give up fast. With the query timeout (3 s) some staging batches timed out (27 Oct
     * 2026).
     */
    @Bean
    EmbeddingIndexer embeddingIndexer(
        ResourceStore store,
        Embedder embedder,
        Clock clock,
        MeterRegistry registry,
        ObservationRegistry observations,
        Resilience resilience,
        EmbeddingProperties embedding,
        CatalogProperties catalog) {
      CatalogProperties.Indexer settings = catalog.indexer();
      Embedder indexing =
          new NvidiaEmbedder(
              embedding.baseUrl().toString(),
              embedding.apiKey(),
              embedder.model(),
              embedder.dimensions(),
              settings.timeout(),
              observations,
              resilience,
              LlmPolicies.EMBED_INDEX);
      return new EmbeddingIndexer(
          store,
          indexing,
          settings.batch(),
          settings.singlesAfterFailure(),
          Duration.ofMinutes(2),
          clock,
          registry);
    }
  }
}
