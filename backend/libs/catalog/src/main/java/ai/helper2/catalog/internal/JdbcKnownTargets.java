package ai.helper2.catalog.internal;

import ai.helper2.catalog.KnownTarget;
import ai.helper2.catalog.KnownTargets;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;

class JdbcKnownTargets implements KnownTargets {

  private final JdbcClient jdbc;

  JdbcKnownTargets(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Map<String, KnownTarget> named(Collection<String> targets) {
    String[] keys =
        targets.stream()
            .map(KnownTarget::key)
            .filter(k -> !k.isEmpty())
            .distinct()
            .toArray(String[]::new);
    if (keys.length == 0) {
      return Map.of();
    }
    Map<String, KnownTarget> named = new LinkedHashMap<>();
    jdbc.sql(
            """
            SELECT n.name_key, t.name, t.kind
            FROM known_target_name n JOIN known_target t ON t.id = n.target_id
            WHERE n.name_key = ANY(:keys)
            """)
        .param("keys", keys)
        .query((rs, i) ->
            named.put(
                rs.getString("name_key"),
                new KnownTarget(
                    rs.getString("name"), KnownTarget.Kind.valueOf(rs.getString("kind")))))
        .list();
    return named;
  }
}
