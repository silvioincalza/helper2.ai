package ai.helper2.catalog.internal;

import ai.helper2.catalog.JobMarkers;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcJobMarkers implements JobMarkers {

  private final JdbcClient jdbc;

  JdbcJobMarkers(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public String lastMigration() {
    return jdbc.sql(
            "SELECT coalesce(max(installed_rank), 0) FROM flyway_schema_history WHERE success")
        .query(Long.class)
        .single()
        .toString();
  }

  @Override
  public Optional<String> marker(String name) {
    return jdbc.sql("SELECT value FROM job_marker WHERE name = :name")
        .param("name", name)
        .query(String.class)
        .optional();
  }

  @Override
  public void mark(String name, String value) {
    jdbc.sql(
            """
            INSERT INTO job_marker (name, value) VALUES (:name, :value)
            ON CONFLICT (name) DO UPDATE SET value = EXCLUDED.value, done_at = now()
            """)
        .param("name", name)
        .param("value", value)
        .update();
  }
}
