package ai.helper2.scout.internal;

import ai.helper2.scout.EnrichmentQueueControl;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The admin's decisions on the enrichment queue, one row per resource in enrichment_queue_control
 * (ADR 0045 §7). The Scout owns the table and is its only writer; the catalog's backlog query reads
 * it.
 */
@Component
class JdbcEnrichmentQueueControl implements EnrichmentQueueControl {

  private final JdbcClient jdbc;

  JdbcEnrichmentQueueControl(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void prioritise(UUID resourceId, Instant at) {
    jdbc.sql(
            """
            INSERT INTO enrichment_queue_control (resource_id, prioritised_at, updated_at)
            VALUES (:id, :at, :at)
            ON CONFLICT (resource_id) DO UPDATE
              SET prioritised_at = :at, skipped_at = NULL, skip_reason = NULL, updated_at = :at
            """)
        .param("id", resourceId)
        .param("at", Timestamp.from(at))
        .update();
  }

  @Override
  public void skip(UUID resourceId, @Nullable String reason, Instant at) {
    jdbc.sql(
            """
            INSERT INTO enrichment_queue_control (resource_id, skipped_at, skip_reason, updated_at)
            VALUES (:id, :at, :reason, :at)
            ON CONFLICT (resource_id) DO UPDATE
              SET skipped_at = :at, skip_reason = :reason, prioritised_at = NULL, updated_at = :at
            """)
        .param("id", resourceId)
        .param("at", Timestamp.from(at))
        .param("reason", reason)
        .update();
  }

  @Override
  public void requeue(UUID resourceId, boolean resetFailures, Instant at) {
    jdbc.sql(
            """
            INSERT INTO enrichment_queue_control (resource_id, failures_reset_at, updated_at)
            VALUES (:id, CASE WHEN :reset THEN CAST(:at AS timestamptz) END, :at)
            ON CONFLICT (resource_id) DO UPDATE
              SET skipped_at = NULL, skip_reason = NULL, updated_at = :at,
                  failures_reset_at = CASE WHEN :reset THEN CAST(:at AS timestamptz)
                                           ELSE enrichment_queue_control.failures_reset_at END
            """)
        .param("id", resourceId)
        .param("at", Timestamp.from(at))
        .param("reset", resetFailures)
        .update();
  }
}
