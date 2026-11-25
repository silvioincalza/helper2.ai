package ai.searchavo.catalog.internal;

import ai.searchavo.catalog.CatalogPolicy;
import ai.searchavo.catalog.CuratedTypes;
import ai.searchavo.catalog.DataOrigin;
import ai.searchavo.catalog.Embedder;
import ai.searchavo.catalog.ResourceCards;
import ai.searchavo.catalog.Resources;
import ai.searchavo.catalog.ReviewQueue;
import ai.searchavo.catalog.ScoutPolicy;
import ai.searchavo.catalog.config.CatalogProperties;
import ai.searchavo.catalog.config.CatalogRetentionProperties;
import ai.searchavo.catalog.config.ScoutEvidenceProperties;
import ai.searchavo.platform.cache.SharedCaches;
import ai.searchavo.platform.config.EmbeddingProperties;
import ai.searchavo.platform.config.HttpClientProperties;
import ai.searchavo.platform.llm.LlmPolicies;
import ai.searchavo.platform.resilience.Resilience;
import ai.searchavo.search.SourceDescriptor;
import ai.searchavo.shared.ResourceType;
import ai.searchavo.shared.SourceMode;
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
  static final String INDEX_SOURCE = "searchavo-index";

  static final String SEMANTIC_SOURCE = "searchavo-semantic";

  @Bean
  CatalogPolicy catalogPolicy(CatalogProperties catalog, CatalogRetentionProperties retention) {
    return new CatalogPolicy(
        retention.resolvedIssueReports(),
        catalog.semantic().queryVectorTtl(),
        retention.webNews(),
        retention.resolvedReviewItems(),
        retention.pastEvents(),
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
        Gauge.builder("searchavo.review.open_items", queue, ReviewQueue::openCount)
            .description("Review items waiting for a decision (status OPEN)")
            .register(meters);
  }

  @Bean
  CatalogSearchSource indexSearchSource(
      JdbcCatalogSearch store, Clock clock, CatalogProperties catalog) {
    SourceDescriptor d =
        SourceDescriptor.inHouse(
                INDEX_SOURCE,
                "searchavo.ai catalog",
                "Catalog",
                SourceMode.INDEXED,
                URI.create("https://searchavo.ai/sources"),
                EnumSet.allOf(ResourceType.class),
                catalog.index().timeout(),
                Duration.ZERO)
            .withAttribution(
                "Our own index, built ahead of time from the sources on this page and from earlier"
                    + " searches. It can be behind the original.")
            .withPriority(50)
            .filteringByDate()
            .browsing();
    return new CatalogSearchSource(
        d, store, Set.of(DataOrigin.INDEXED, DataOrigin.OBSERVED), clock);
  }

  @Bean
  CatalogSearchSource curatedSearchSource(
      JdbcCatalogSearch store, Clock clock, CatalogProperties catalog) {
    SourceDescriptor d =
        SourceDescriptor.inHouse(
                "curated",
                "Hand-picked list",
                "Curated",
                SourceMode.CURATED,
                URI.create("https://searchavo.ai/sources"),
                EnumSet.copyOf(CuratedTypes.TYPES),
                catalog.curated().timeout(),
                Duration.ZERO)
            .withAttribution(
                "A starter list written by searchavo.ai. Not live. Dates are unknown unless a source"
                    + " states them.")
            .enabledWhen(catalog.curated().enabled())
            .withPriority(10)
            .filteringByDate()
            .browsing();
    return new CatalogSearchSource(d, store, Set.of(DataOrigin.CURATED), clock);
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnExpression("'${searchavo.embedding.api-key:}' != ''")
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
      SourceDescriptor d =
          SourceDescriptor.inHouse(
                  SEMANTIC_SOURCE,
                  "searchavo.ai catalog, by meaning",
                  "Meaning",
                  SourceMode.INDEXED,
                  URI.create("https://searchavo.ai/sources"),
                  EnumSet.allOf(ResourceType.class),
                  semantic.timeout(),
                  Duration.ZERO)
              .withAttribution(
                  "Our own index, searched by meaning with the "
                      + embedder.model()
                      + " embedding model")
              .withPriority(55)
              .askedWithoutTranslation();
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
