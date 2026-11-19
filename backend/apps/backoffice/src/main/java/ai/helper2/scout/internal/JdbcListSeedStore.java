package ai.helper2.scout.internal;

import ai.helper2.scout.ListKind;
import ai.helper2.scout.ListSeed;
import ai.helper2.scout.ListSeedStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
class JdbcListSeedStore implements ListSeedStore {

  private static final String COLUMNS = "kind, address, added_by, added_at, reason, read_tried_at";

  private final JdbcClient jdbc;

  JdbcListSeedStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public List<ListSeed> all() {
    return jdbc.sql("SELECT " + COLUMNS + " FROM list_seed ORDER BY added_at, address")
        .query((rs, i) -> seed(rs))
        .list();
  }

  @Override
  public boolean add(ListSeed seed) {
    return jdbc.sql(
                """
                INSERT INTO list_seed (kind, address, added_by, added_at, reason)
                VALUES (:kind, :address, :by, :at, :reason)
                ON CONFLICT (address) DO NOTHING
                """)
            .param("kind", seed.getKind().name())
            .param("address", seed.getAddress())
            .param("by", seed.getAddedBy())
            .param("at", Timestamp.from(seed.getAddedAt()))
            .param("reason", seed.getReason())
            .update()
        == 1;
  }

  @Override
  public @Nullable ListSeed remove(String address) {
    return jdbc.sql("DELETE FROM list_seed WHERE address = :address RETURNING " + COLUMNS)
        .param("address", address)
        .query((rs, i) -> seed(rs))
        .optional()
        .orElse(null);
  }

  @Override
  public void readTried(String address, Instant at) {
    jdbc.sql(
            "UPDATE list_seed SET read_tried_at = :at WHERE address = :address AND kind = 'WEB_PAGE'")
        .param("at", Timestamp.from(at))
        .param("address", address)
        .update();
  }

  private static ListSeed seed(ResultSet rs) throws SQLException {
    Timestamp tried = rs.getTimestamp("read_tried_at");
    return new ListSeed(
        ListKind.valueOf(rs.getString("kind")),
        rs.getString("address"),
        rs.getString("added_by"),
        rs.getTimestamp("added_at").toInstant(),
        rs.getString("reason"),
        tried == null ? null : tried.toInstant());
  }
}
