package ai.helper2.catalog.internal;

import ai.helper2.catalog.CategoryAssignments;
import ai.helper2.catalog.CategoryInputs;
import ai.helper2.shared.CategoryInput;
import ai.helper2.shared.ResourceType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.apache.commons.lang3.EnumUtils;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcCategoryLabels implements CategoryInputs, CategoryAssignments {

  private final JdbcClient jdbc;
  private final JdbcTemplate template;

  JdbcCategoryLabels(JdbcClient jdbc, JdbcTemplate template) {
    this.jdbc = jdbc;
    this.template = template;
  }

  @Override
  public List<UUID> resourceIdsAfter(@Nullable UUID after, int limit) {
    return jdbc.sql("SELECT id FROM resource WHERE id > :after ORDER BY id LIMIT :limit")
        .param("after", after == null ? new UUID(0, 0) : after)
        .param("limit", limit)
        .query(UUID.class)
        .list();
  }

  @Override
  public List<Categorizable> of(Collection<UUID> resourceIds) {
    return jdbc.sql(
            """
            SELECT r.id, r.title, r.type,
                   (SELECT string_agg(DISTINCT sr.description, E'\\n') FROM source_record sr
                    WHERE sr.resource_id = r.id AND sr.description IS NOT NULL
                      AND coalesce(sr.extra ->> 'descriptionOrigin', 'SOURCE_PROVIDED') NOT LIKE 'AI\\_%') AS description,
                   ARRAY(SELECT DISTINCT t.value FROM source_record sr
                         CROSS JOIN LATERAL jsonb_array_elements_text(
                             CASE WHEN jsonb_typeof(sr.extra -> 'tags') = 'array' THEN sr.extra -> 'tags'
                                  ELSE '[]'::jsonb END) AS t(value)
                         WHERE sr.resource_id = r.id) AS tags,
                   ARRAY(SELECT sr.source_id FROM source_record sr WHERE sr.resource_id = r.id
                         UNION
                         SELECT f ->> 'sourceId' FROM source_record sr
                         CROSS JOIN LATERAL jsonb_array_elements(
                             CASE WHEN jsonb_typeof(sr.extra -> 'foundBy') = 'array' THEN sr.extra -> 'foundBy'
                                  ELSE '[]'::jsonb END) AS f
                         WHERE sr.resource_id = r.id AND f ->> 'sourceId' IS NOT NULL) AS finders,
                   ARRAY(SELECT DISTINCT e.heading_path FROM awesome_list_entry e
                         WHERE e.resource_id = r.id AND e.heading_path <> '') AS headings
            FROM resource r WHERE r.id = ANY(:ids)
            """)
        .param("ids", resourceIds.toArray(UUID[]::new))
        .query(JdbcCategoryLabels::categorizable)
        .list();
  }

  private static Categorizable categorizable(ResultSet rs, int row) throws SQLException {
    return new Categorizable(
        rs.getObject("id", UUID.class),
        new CategoryInput(
            rs.getString("title"),
            rs.getString("description"),
            textArray(rs, "tags"),
            EnumUtils.getEnum(ResourceType.class, rs.getString("type")),
            textArray(rs, "finders"),
            textArray(rs, "headings")));
  }

  private static List<String> textArray(ResultSet rs, String column) throws SQLException {
    java.sql.Array array = rs.getArray(column);
    if (array == null) {
      return List.of();
    }
    return Arrays.stream((Object[]) array.getArray())
        .filter(Objects::nonNull)
        .map(Object::toString)
        .toList();
  }

  @Override
  public void replace(Collection<UUID> resourceIds, List<Assignment> assignments) {
    jdbc.sql("DELETE FROM resource_label WHERE resource_id = ANY(:ids) AND kind = 'CATEGORY'")
        .param("ids", resourceIds.toArray(UUID[]::new))
        .update();
    template.batchUpdate(
        "INSERT INTO resource_label (resource_id, kind, value) VALUES (?, 'CATEGORY', ?)"
            + " ON CONFLICT DO NOTHING",
        assignments.stream().map(a -> new Object[] {a.resourceId(), a.category()}).toList());
  }
}
