package ai.helper2.catalog.internal;

import ai.helper2.catalog.enrichment.SourceOutcomes;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

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

  public record RecordedOutcome(String sourceId, String outcome, Instant checkedAt) {}

  public List<RecordedOutcome> recordedOutcomes() {
    return jdbc.sql(
            "SELECT id, last_outcome, last_checked_at FROM source WHERE last_outcome IS NOT NULL AND last_checked_at IS NOT NULL")
        .query((rs, i) ->
            new RecordedOutcome(
                rs.getString("id"),
                rs.getString("last_outcome"),
                rs.getTimestamp("last_checked_at").toInstant()))
        .list();
  }
}
