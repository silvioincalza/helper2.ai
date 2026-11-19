package ai.helper2.catalog.internal;

import ai.helper2.catalog.Embedder;
import ai.helper2.catalog.EmbeddingStore;
import ai.helper2.catalog.KnownTargets;
import ai.helper2.catalog.ReviewQueue;
import ai.helper2.catalog.config.CatalogProperties;
import ai.helper2.catalog.usecase.IndexEmbeddings;
import ai.helper2.platform.config.EmbeddingProperties;
import ai.helper2.platform.config.HttpClientProperties;
import ai.helper2.platform.llm.LlmPolicies;
import ai.helper2.platform.resilience.Resilience;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

public class CatalogBackOfficeAdapters {

  @Bean
  JdbcRelationshipTargets jdbcRelationshipTargets(JdbcClient jdbc) {
    return new JdbcRelationshipTargets(jdbc);
  }

  @Bean
  JdbcValidationIssues jdbcValidationIssues(JdbcClient jdbc) {
    return new JdbcValidationIssues(jdbc);
  }

  @Bean
  JdbcAiCheckReport jdbcAiCheckReport(JdbcClient jdbc) {
    return new JdbcAiCheckReport(jdbc);
  }

  @Bean
  JdbcAwesomeListSuspects jdbcAwesomeListSuspects(JdbcClient jdbc) {
    return new JdbcAwesomeListSuspects(jdbc);
  }

  @Bean
  JdbcKnownTargets jdbcKnownTargets(JdbcClient jdbc) {
    return new JdbcKnownTargets(jdbc);
  }

  @Bean
  JdbcKnownTargetList jdbcKnownTargetList(JdbcClient jdbc) {
    return new JdbcKnownTargetList(jdbc);
  }

  @Bean
  JdbcKnownTargetCandidates jdbcKnownTargetCandidates(JdbcClient jdbc, ObjectMapper json) {
    return new JdbcKnownTargetCandidates(jdbc, new KnownTargetProposalJson(json));
  }

  @Bean
  JdbcCuratedFields jdbcCuratedFields(JdbcClient jdbc) {
    return new JdbcCuratedFields(jdbc);
  }

  @Bean
  JdbcReviewDecisions jdbcReviewDecisions(JdbcClient jdbc) {
    return new JdbcReviewDecisions(jdbc);
  }

  @Bean
  JdbcResourceRemoval jdbcResourceRemoval(JdbcClient jdbc) {
    return new JdbcResourceRemoval(jdbc);
  }

  @Bean
  JdbcCatalogRetention jdbcCatalogRetention(JdbcClient jdbc, JdbcCatalogWriter writer) {
    return new JdbcCatalogRetention(jdbc, writer);
  }

  @Bean
  JdbcAwesomeListIndex jdbcAwesomeListIndex(JdbcClient jdbc, ObjectMapper json) {
    return new JdbcAwesomeListIndex(jdbc, json);
  }

  @Bean
  JdbcReviewEvidence jdbcReviewEvidence(JdbcClient jdbc) {
    return new JdbcReviewEvidence(jdbc);
  }

  @Bean
  JdbcRepositoryVisits jdbcRepositoryVisits(JdbcClient jdbc) {
    return new JdbcRepositoryVisits(jdbc);
  }

  @Bean
  JdbcEnrichmentCandidates jdbcEnrichmentCandidates(JdbcClient jdbc) {
    return new JdbcEnrichmentCandidates(jdbc);
  }

  @Bean
  JdbcEnrichmentResults jdbcEnrichmentResults(
      JdbcClient jdbc,
      ReviewQueue reviewQueue,
      ObjectMapper json,
      Clock clock,
      KnownTargets knownTargets) {
    return new JdbcEnrichmentResults(jdbc, reviewQueue, json, clock, knownTargets);
  }

  @Bean
  JdbcEmbeddingStore jdbcEmbeddingStore(JdbcClient jdbc, TransactionTemplate tx) {
    return new JdbcEmbeddingStore(jdbc, tx);
  }

  @Bean
  @ConditionalOnExpression("'${helper2.embedding.api-key:}' != ''")
  IndexEmbeddings indexEmbeddings(
      EmbeddingStore store,
      Embedder embedder,
      Clock clock,
      MeterRegistry registry,
      HttpClientProperties http,
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
            http,
            observations,
            resilience,
            LlmPolicies.EMBED_INDEX);
    return new IndexEmbeddings(
        store,
        indexing,
        settings.batch(),
        settings.singlesAfterFailure(),
        Duration.ofMinutes(2),
        clock,
        registry);
  }
}
