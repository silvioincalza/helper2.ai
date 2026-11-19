package ai.helper2.scout.internal;

import ai.helper2.scout.ListSeed;
import ai.helper2.scout.ListSeedStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
class JdbcListSeedStore implements ListSeedStore {

  private final JdbcClient jdbc;

  JdbcListSeedStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public List<ListSeed> all() {
    return jdbc.sql(
            "SELECT repository, added_by, added_at, reason FROM list_seed"
                + " ORDER BY added_at, repository")
        .query((rs, i) -> seed(rs))
        .list();
  }

  @Override
  public boolean add(ListSeed seed) {
    return jdbc.sql(
                """
                INSERT INTO list_seed (repository, added_by, added_at, reason)
                VALUES (:repository, :by, :at, :reason)
                ON CONFLICT (repository) DO NOTHING
                """)
            .param("repository", seed.getRepository())
            .param("by", seed.getAddedBy())
            .param("at", Timestamp.from(seed.getAddedAt()))
            .param("reason", seed.getReason())
            .update()
        == 1;
  }

  @Override
  public @Nullable ListSeed remove(String repository) {
    return jdbc.sql(
            """
            DELETE FROM list_seed WHERE repository = :repository
            RETURNING repository, added_by, added_at, reason
            """)
        .param("repository", repository)
        .query((rs, i) -> seed(rs))
        .optional()
        .orElse(null);
  }

  private static ListSeed seed(ResultSet rs) throws SQLException {
    return new ListSeed(
        rs.getString("repository"),
        rs.getString("added_by"),
        rs.getTimestamp("added_at").toInstant(),
        rs.getString("reason"));
  }
}
