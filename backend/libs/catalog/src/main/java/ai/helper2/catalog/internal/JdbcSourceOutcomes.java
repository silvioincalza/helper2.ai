package ai.helper2.catalog.internal;

import ai.helper2.catalog.enrichment.SourceOutcomes;
import java.util.Collection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** The last run outcome of each source, in the source table. */
@Component
class JdbcSourceOutcomes implements SourceOutcomes {

  private final JdbcClient jdbc;

  JdbcSourceOutcomes(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void record(String sourceId, String outcome) {
    jdbc.sql("UPDATE source SET last_outcome = :o, last_checked_at = now() WHERE id = :id")
        .param("o", outcome)
        .param("id", sourceId)
        .update();
  }

  @Override
  public void markFailed(Collection<String> sourceIds) {
    if (sourceIds.isEmpty()) {
      return;
    }
    jdbc.sql(
            "UPDATE source SET last_outcome = 'FAILED', last_checked_at = now() WHERE id IN (:ids)")
        .param("ids", sourceIds)
        .update();
  }
}
