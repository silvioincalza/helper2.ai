package ai.helper2.catalog.internal;

import ai.helper2.catalog.CuratedFields;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

class JdbcCuratedFields implements CuratedFields {

  private static final Map<String, String> VALUES =
      Map.of(
          "type", "type",
          "pricing", "pricing",
          "license", "license",
          "openSource", "CAST(open_source AS text)",
          "techLevel", "tech_level",
          "audience", "audience",
          "language", "language",
          "description", "description",
          "publishedAt", "to_char(published_at AT TIME ZONE 'UTC', 'YYYY-MM-DD')");

  private final JdbcClient jdbc;

  JdbcCuratedFields(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<Current> current(UUID resourceId, String field) {
    String value = VALUES.get(field);
    if (value == null) {
      return Optional.empty();
    }
    return jdbc.sql(
            "SELECT %s AS value, :field = ANY(curated_fields) AS curated FROM resource WHERE id = :id"
                .formatted(value))
        .param("field", field)
        .param("id", resourceId)
        .query((rs, i) -> new Current(rs.getString("value"), rs.getBoolean("curated")))
        .optional();
  }

  @Override
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

  @Override
  public boolean setDescription(UUID resourceId, String description, String origin) {
    return jdbc.sql(
                """
                UPDATE resource SET description = :d, description_origin = :o, modified_at = now(),
                    curated_fields = CASE WHEN 'description' = ANY(curated_fields) THEN curated_fields
                                          ELSE array_append(curated_fields, 'description') END
                WHERE id = :id
                """)
            .param("d", description)
            .param("o", origin)
            .param("id", resourceId)
            .update()
        == 1;
  }

  @Override
  public boolean setPublished(UUID resourceId, LocalDate publishedAt) {
    int updated =
        jdbc.sql(
                """
                UPDATE resource SET published_at = :at, published_precision = 'DAY', published_source_id = NULL,
                    modified_at = now(),
                    curated_fields = CASE WHEN 'publishedAt' = ANY(curated_fields) THEN curated_fields
                                          ELSE array_append(curated_fields, 'publishedAt') END
                WHERE id = :id
                """)
            .param("at", Timestamp.from(publishedAt.atStartOfDay(ZoneOffset.UTC).toInstant()))
            .param("id", resourceId)
            .update();
    if (updated == 0) {
      return false;
    }
    jdbc.sql(
            """
            DELETE FROM field_assertion WHERE resource_id = :id AND field = 'publishedAt'
              AND origin = 'CURATED' AND enrichment_run_id IS NULL
            """)
        .param("id", resourceId)
        .update();
    jdbc.sql(
            """
            INSERT INTO field_assertion (id, resource_id, field, value, origin, verification)
            VALUES (:aid, :id, 'publishedAt', :v, 'CURATED', 'VERIFIED')
            """)
        .param("aid", UUID.randomUUID())
        .param("id", resourceId)
        .param("v", publishedAt.toString())
        .update();
    return true;
  }

  @Override
  public boolean exists(UUID resourceId) {
    return jdbc.sql("SELECT EXISTS (SELECT 1 FROM resource WHERE id = :id)")
        .param("id", resourceId)
        .query(Boolean.class)
        .single();
  }

  @Override
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
