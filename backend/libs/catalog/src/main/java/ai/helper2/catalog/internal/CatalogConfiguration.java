package ai.helper2.catalog.internal;

import ai.helper2.catalog.CatalogPolicy;
import ai.helper2.catalog.CuratedTypes;
import ai.helper2.catalog.DataOrigin;
import ai.helper2.catalog.Embedder;
import ai.helper2.catalog.ResourceCards;
import ai.helper2.catalog.Resources;
import ai.helper2.catalog.ReviewQueue;
import ai.helper2.catalog.ScoutPolicy;
import ai.helper2.catalog.config.CatalogProperties;
import ai.helper2.catalog.config.CatalogRetentionProperties;
import ai.helper2.catalog.config.ScoutEvidenceProperties;
import ai.helper2.platform.cache.SharedCaches;
import ai.helper2.platform.config.EmbeddingProperties;
import ai.helper2.platform.config.HttpClientProperties;
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
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ScoutEvidenceProperties.class)
class CatalogConfiguration {
  static final String INDEX_SOURCE = "helper2-index";

  static final String SEMANTIC_SOURCE = "helper2-semantic";

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

  @Bean
  Reconciliation reconciliation(
      CatalogProperties catalog,
      JdbcClient jdbc,
      ObjectMapper json,
      ResourceRows rows,
      Resources resources,
      ResourceCards cards) {
    if (catalog.reconcileByProjection()) {
      return new ProjectedReconciliation(jdbc, json, rows, resources, cards);
    }
    return new SqlReconciliation(jdbc);
  }

  @Bean
  ScoutPolicy scoutPolicy(ScoutEvidenceProperties evidence) {
    return ScoutPolicy.of(evidence.pageTextTtl(), evidence.pageReadTtl());
  }

  @Bean(destroyMethod = "close")
  ObservationQueue observationQueue(MeterRegistry registry, CatalogProperties catalog) {
    return new ObservationQueue(catalog.observationQueue(), registry);
  }

  @Bean
  MeterBinder reviewBacklogGauge(ReviewQueue queue) {
    return meters ->
        Gauge.builder("helper2.review.open_items", queue, ReviewQueue::openCount)
            .description("Review items waiting for a decision (status OPEN)")
            .register(meters);
  }

  @Bean
  CatalogSearchSource indexSearchSource(
      JdbcCatalogSearch store, Clock clock, CatalogProperties catalog) {
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
            "helper2.ai catalog",
            "Catalog",
            false,
            SourceMode.INDEXED,
            URI.create("https://helper2.ai/sources"),
            null,
            "Our own index, built ahead of time from the sources on this page and from earlier"
                + " searches. It can be behind the original.",
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
      JdbcCatalogSearch store, Clock clock, CatalogProperties catalog) {
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
            "A starter list written by helper2.ai. Not live. Dates are unknown unless a source states"
                + " them.",
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

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnExpression("'${helper2.embedding.api-key:}' != ''")
  static class VectorSearch {

    private static final Logger log = LoggerFactory.getLogger(VectorSearch.class);

    @Bean
    Embedder embedder(
        EmbeddingProperties embedding,
        HttpClientProperties http,
        ObservationRegistry observations,
        Resilience resilience) {
      log.info(
          "Vector search on: model={}, dimensions={}", embedding.model(), embedding.dimensions());
      return new NvidiaEmbedder(
          embedding.baseUrl().toString(),
          embedding.apiKey(),
          embedding.model(),
          embedding.dimensions(),
          embedding.timeout(),
          http,
          observations,
          resilience,
          LlmPolicies.EMBED);
    }

    @Bean
    SemanticSearchSource semanticSearchSource(
        JdbcCatalogSearch store,
        Embedder embedder,
        Clock clock,
        SharedCaches caches,
        CatalogProperties catalog) {
      CatalogProperties.Semantic semantic = catalog.semantic();
      Duration timeout = semantic.timeout();
      boolean enabled = true;
      int priority = 55;
      boolean usesWebQuery = false;
      boolean usesTranslation = false;
      SourceDescriptor d =
          new SourceDescriptor(
              SEMANTIC_SOURCE,
              "helper2.ai catalog, by meaning",
              "Meaning",
              false,
              SourceMode.INDEXED,
              URI.create("https://helper2.ai/sources"),
              null,
              "Our own index, searched by meaning with the "
                  + embedder.model()
                  + " embedding model",
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
  }
}
