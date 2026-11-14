package ai.helper2.examples.internal;

import ai.helper2.examples.HomeExample;
import ai.helper2.examples.ServedExamples;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcServedExamples implements ServedExamples {

  private final JdbcClient jdbc;

  JdbcServedExamples(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public List<HomeExample> served(String locale, int limit) {
    return jdbc.sql(
            """
            SELECT * FROM home_example
            WHERE locale = :locale AND status = 'ACTIVE' AND check_outcome = 'PASS'
            ORDER BY path, lower(label)
            LIMIT :limit
            """)
        .param("locale", locale)
        .param("limit", limit)
        .query(JdbcHomeExamples::example)
        .list();
  }
}
