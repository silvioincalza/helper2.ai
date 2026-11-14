package ai.helper2.catalog.internal;

import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

public class CatalogBackOfficeAdapters {

  @Bean
  JdbcRelationshipTargets jdbcRelationshipTargets(JdbcClient jdbc) {
    return new JdbcRelationshipTargets(jdbc);
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
  JdbcKnownTargetCandidates jdbcKnownTargetCandidates(JdbcClient jdbc) {
    return new JdbcKnownTargetCandidates(jdbc);
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
  JdbcEnrichmentCandidates jdbcEnrichmentCandidates(JdbcClient jdbc) {
    return new JdbcEnrichmentCandidates(jdbc);
  }
}
