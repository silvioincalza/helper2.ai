package ai.searchavo.catalog.internal;

import ai.searchavo.catalog.AwesomeListSuspects;
import ai.searchavo.shared.ResourceType;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

class JdbcAwesomeListSuspects implements AwesomeListSuspects {

  private final JdbcClient jdbc;

  JdbcAwesomeListSuspects(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public List<Suspect> suspects(int limit) {
    return jdbc.sql(
            """
            SELECT r.id, substring(r.canonical_url FROM '^https://github\\.com/([^/]+/[^/]+)$') AS repo, r.type,
                   'type' = ANY(r.curated_fields) AS curated, t.topics
              FROM resource r
              CROSS JOIN LATERAL (
                SELECT coalesce(array_agg(DISTINCT lower(v.tag) ORDER BY lower(v.tag)), '{}') AS topics
                  FROM source_record sr
                  CROSS JOIN LATERAL jsonb_array_elements_text(
                    CASE WHEN jsonb_typeof(sr.extra -> 'tags') = 'array' THEN sr.extra -> 'tags'
                         ELSE '[]'::jsonb END) AS v(tag)
                 WHERE sr.resource_id = r.id
              ) t
             WHERE r.type <> 'AWESOME_LIST'
               AND r.canonical_url ~* '^https://github\\.com/[A-Za-z0-9-]+/[^/]+$'
               AND (r.canonical_url ~* '^https://github\\.com/[A-Za-z0-9-]+/(awesome|best-of-)[^/]*$'
                    OR 'awesome-list' = ANY(t.topics))
             ORDER BY r.popularity DESC, r.id
             LIMIT :limit
            """)
        .param("limit", limit)
        .query((rs, i) ->
            new Suspect(
                rs.getObject("id", UUID.class),
                rs.getString("repo"),
                Arrays.asList((String[]) rs.getArray("topics").getArray()),
                ResourceType.valueOf(rs.getString("type")),
                rs.getBoolean("curated")))
        .list();
  }
}
