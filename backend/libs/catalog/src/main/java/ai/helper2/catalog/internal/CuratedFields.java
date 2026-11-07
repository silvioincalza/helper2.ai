package ai.helper2.catalog.internal;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * A resource's fields an admin set (ADR 0026): the value in the resource's column, the field in
 * {@code curated_fields}, and a CURATED field assertion that says where the value came from. Call
 * inside a transaction.
 */
@Component
public class CuratedFields {

  private final JdbcClient jdbc;

  CuratedFields(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Writes [value] to [column] of the resource and lists [field] as curated; false when there is no
   * such resource. [column] is the caller's constant, never input.
   */
  public boolean set(UUID resourceId, String field, String column, boolean bool, String value) {
    int updated =
        jdbc.sql(
                """
                UPDATE resource SET %s = %s, modified_at = now(),
                    curated_fields = CASE WHEN :field = ANY(curated_fields) THEN curated_fields
                                          ELSE array_append(curated_fields, :field) END
                WHERE id = :id
                """
                    .formatted(column, bool ? "CAST(:v AS boolean)" : ":v"))
            .param("field", field)
            .param("v", value)
            .param("id", resourceId)
            .update();
    if (updated == 0) {
      return false;
    }
    jdbc.sql(
            """
            DELETE FROM field_assertion WHERE resource_id = :id AND field = :field
              AND origin = 'CURATED' AND enrichment_run_id IS NULL
            """)
        .param("id", resourceId)
        .param("field", field)
        .update();
    jdbc.sql(
            """
            INSERT INTO field_assertion (id, resource_id, field, value, origin, verification)
            VALUES (:aid, :id, :field, :v, 'CURATED', 'VERIFIED')
            """)
        .param("aid", UUID.randomUUID())
        .param("id", resourceId)
        .param("field", field)
        .param("v", value)
        .update();
    return true;
  }

  public boolean exists(UUID resourceId) {
    return jdbc.sql("SELECT EXISTS (SELECT 1 FROM resource WHERE id = :id)")
        .param("id", resourceId)
        .query(Boolean.class)
        .single();
  }

  /** The AI's values of [field], rejected as the code re-check does (ADR 0023). */
  public int rejectAiValues(UUID resourceId, String field) {
    return jdbc.sql(
            """
            UPDATE field_assertion SET verification = 'REJECTED'
            WHERE resource_id = :id AND field = :field AND verification <> 'REJECTED'
              AND origin IN ('AI_EXTRACTED', 'AI_ESTIMATED', 'SELF_DECLARED')
            """)
        .param("id", resourceId)
        .param("field", field)
        .update();
  }
}
