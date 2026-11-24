package ai.searchavo.scout.internal;

import ai.searchavo.scout.DiscoveryHost;
import ai.searchavo.scout.DiscoveryHostStore;
import ai.searchavo.scout.HostKind;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
class JdbcDiscoveryHostStore implements DiscoveryHostStore {

  private static final String COLUMNS = "host, kind, added_by, added_at, reason, reads_lists";

  private final JdbcClient jdbc;

  JdbcDiscoveryHostStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public List<DiscoveryHost> all() {
    return jdbc.sql("SELECT " + COLUMNS + " FROM discovery_host ORDER BY kind, host")
        .query((rs, i) -> JdbcDiscoveryHostStore.host(rs))
        .list();
  }

  @Override
  public boolean add(DiscoveryHost host) {
    return jdbc.sql(
                """
                INSERT INTO discovery_host (host, kind, added_by, added_at, reason)
                VALUES (:host, :kind, :by, :at, :reason)
                ON CONFLICT (host) DO NOTHING
                """)
            .param("host", host.getHost())
            .param("kind", host.getKind().name())
            .param("by", host.getAddedBy())
            .param("at", Timestamp.from(host.getAddedAt()))
            .param("reason", host.getReason())
            .update()
        == 1;
  }

  @Override
  public @Nullable DiscoveryHost remove(String host) {
    return jdbc.sql(
            """
            DELETE FROM discovery_host WHERE host = :host
            RETURNING host, kind, added_by, added_at, reason, reads_lists
            """)
        .param("host", host)
        .query((rs, i) -> JdbcDiscoveryHostStore.host(rs))
        .optional()
        .orElse(null);
  }

  @Override
  public @Nullable DiscoveryHost readLists(String host, boolean on) {
    return jdbc.sql(
            """
            UPDATE discovery_host SET reads_lists = :on WHERE host = :host AND kind = 'DIRECTORY'
            RETURNING host, kind, added_by, added_at, reason, reads_lists
            """)
        .param("on", on)
        .param("host", host)
        .query((rs, i) -> host(rs))
        .optional()
        .orElse(null);
  }

  private static DiscoveryHost host(ResultSet rs) throws SQLException {
    return new DiscoveryHost(
        rs.getString("host"),
        HostKind.valueOf(rs.getString("kind")),
        rs.getString("added_by"),
        rs.getTimestamp("added_at").toInstant(),
        rs.getString("reason"),
        rs.getBoolean("reads_lists"));
  }
}
