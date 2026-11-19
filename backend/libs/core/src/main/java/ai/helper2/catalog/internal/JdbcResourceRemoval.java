package ai.helper2.catalog.internal;

import ai.helper2.catalog.ResourceRemoval;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

class JdbcResourceRemoval implements ResourceRemoval {

  private final JdbcClient jdbc;

  JdbcResourceRemoval(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<Removed> remove(UUID resourceId) {
    ResourceRemovalLock.forRemoval(jdbc);
    Optional<String> url =
        jdbc.sql("SELECT canonical_url FROM resource WHERE id = :id FOR UPDATE")
            .param("id", resourceId)
            .query(String.class)
            .optional();
    if (url.isEmpty()) {
      return Optional.empty();
    }
    jdbc.sql(
            """
            INSERT INTO candidate_decision (kind, candidate_key, status, decided_at)
            SELECT kind, %s, 'REJECTED', now()
            FROM unnest(ARRAY['RESOURCE_CANDIDATE', 'COURSE_CANDIDATE']) AS kind
            ON CONFLICT (kind, candidate_key) DO UPDATE
              SET status = EXCLUDED.status, decided_at = EXCLUDED.decided_at
            """
                .formatted(UrlKeys.keyOf("CAST(:url AS text)")))
        .param("url", url.get())
        .update();
    jdbc.sql(
            """
            INSERT INTO removed_resource (resource_key, removed_at) VALUES (:id, now())
            ON CONFLICT (resource_key) DO UPDATE SET removed_at = EXCLUDED.removed_at
            """)
        .param("id", resourceId)
        .update();
    jdbc.sql("DELETE FROM resource WHERE id = :id").param("id", resourceId).update();
    return Optional.of(new Removed(url.get()));
  }
}
