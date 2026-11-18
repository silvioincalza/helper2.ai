package ai.helper2.catalog.internal;

import ai.helper2.catalog.AwesomeListSuspects;
import ai.helper2.shared.ResourceType;
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
            SELECT id, substring(canonical_url FROM '^https://github\\.com/([^/]+/[^/]+)$') AS repo, type,
                   'type' = ANY(curated_fields) AS curated
              FROM resource
             WHERE type <> 'AWESOME_LIST'
               AND canonical_url ~* '^https://github\\.com/[A-Za-z0-9-]+/awesome[^/]*$'
             ORDER BY popularity DESC, id
             LIMIT :limit
            """)
        .param("limit", limit)
        .query((rs, i) ->
            new Suspect(
                rs.getObject("id", UUID.class),
                rs.getString("repo"),
                ResourceType.valueOf(rs.getString("type")),
                rs.getBoolean("curated")))
        .list();
  }
}
