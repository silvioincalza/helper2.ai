package ai.helper2.search.internal;

import ai.helper2.search.ShadowLibrary;
import ai.helper2.search.ShadowLibraryStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;

public class JdbcShadowLibraryStore implements ShadowLibraryStore {

  private static final String COLUMNS = "name, added_by, added_at, reason";

  private final JdbcClient jdbc;

  public JdbcShadowLibraryStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public List<ShadowLibrary> all() {
    return jdbc.sql("SELECT " + COLUMNS + " FROM shadow_library ORDER BY name")
        .query(JdbcShadowLibraryStore::row)
        .list();
  }

  @Override
  public Set<String> names() {
    return jdbc.sql("SELECT name FROM shadow_library").query(String.class).set();
  }

  @Override
  public boolean add(ShadowLibrary library) {
    return jdbc.sql(
                """
                INSERT INTO shadow_library (name, added_by, added_at, reason)
                VALUES (:name, :by, :at, :reason)
                ON CONFLICT (name) DO NOTHING
                """)
            .param("name", library.getName())
            .param("by", library.getAddedBy())
            .param("at", Timestamp.from(library.getAddedAt()))
            .param("reason", library.getReason())
            .update()
        == 1;
  }

  @Override
  public @Nullable ShadowLibrary remove(String name) {
    return jdbc.sql("DELETE FROM shadow_library WHERE name = :name RETURNING " + COLUMNS)
        .param("name", name)
        .query(JdbcShadowLibraryStore::row)
        .optional()
        .orElse(null);
  }

  private static ShadowLibrary row(ResultSet rs, int i) throws SQLException {
    return new ShadowLibrary(
        rs.getString("name"),
        rs.getString("added_by"),
        rs.getTimestamp("added_at").toInstant(),
        rs.getString("reason"));
  }
}
