package ai.searchavo.catalog.internal;

import ai.searchavo.catalog.AiCheckReport;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;

class JdbcAiCheckReport implements AiCheckReport {

  private final JdbcClient jdbc;

  JdbcAiCheckReport(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public List<Row> mostPopular(@Nullable Cursor after, int size) {
    return jdbc.sql(
            """
            SELECT r.id, r.title, r.description, r.type, r.popularity,
                   coalesce((SELECT array_agg(l.value ORDER BY l.value) FROM resource_label l
                             WHERE l.resource_id = r.id AND l.kind = 'TAG'), '{}') AS tags
            FROM resource r
            WHERE r.data_origin IN ('INDEXED', 'OBSERVED')
              AND NOT EXISTS (SELECT 1 FROM source_record s
                              WHERE s.resource_id = r.id AND s.source_id NOT IN (:general))
              AND (CAST(:afterValue AS numeric) IS NULL
                   OR (r.popularity, r.id) < (CAST(:afterValue AS numeric), CAST(:afterId AS uuid)))
            ORDER BY r.popularity DESC, r.id DESC
            LIMIT :size
            """)
        .param("afterValue", after == null ? null : after.popularity())
        .param("afterId", after == null ? null : after.id())
        .param("size", size)
        .param("general", GENERAL_SOURCES)
        .query((rs, n) ->
            new Row(
                rs.getObject("id", UUID.class),
                rs.getString("title"),
                rs.getString("description"),
                Arrays.asList((String[]) rs.getArray("tags").getArray()),
                rs.getString("type"),
                rs.getBigDecimal("popularity")))
        .list();
  }
}
