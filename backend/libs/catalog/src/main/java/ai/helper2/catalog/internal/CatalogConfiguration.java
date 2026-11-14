package ai.helper2.catalog.internal;

import ai.helper2.catalog.CatalogPolicy;
import ai.helper2.catalog.Embedder;
import ai.helper2.catalog.ResourceCards;
import ai.helper2.catalog.Resources;
import ai.helper2.catalog.ReviewQueue;
import ai.helper2.catalog.ScoutPolicy;
import ai.helper2.catalog.config.CatalogProperties;
import ai.helper2.catalog.config.CatalogRetentionProperties;
import ai.helper2.catalog.config.ScoutEvidenceProperties;
import ai.helper2.platform.config.EmbeddingProperties;
import ai.helper2.platform.config.HttpClientProperties;
import ai.helper2.platform.llm.LlmPolicies;
import ai.helper2.platform.resilience.Resilience;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.observation.ObservationRegistry;
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
  }
}
