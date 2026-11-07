package ai.helper2.scout.internal;

import ai.helper2.scout.DiscoveryHost;
import ai.helper2.scout.DiscoveryHostStore;
import ai.helper2.scout.HostKind;
import java.sql.Timestamp;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Discovery's host lists, one row per host in discovery_host (ADR 0022). The Scout owns the table
 * and is its only writer; the collectors read it once per run.
 */
@Component
class JdbcDiscoveryHostStore implements DiscoveryHostStore {

  private final JdbcClient jdbc;

  JdbcDiscoveryHostStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public List<DiscoveryHost> all() {
    return jdbc.sql(
            "SELECT host, kind, added_by, added_at, reason FROM discovery_host ORDER BY kind, host")
        .query((rs, i) ->
            new DiscoveryHost(
                rs.getString("host"),
                HostKind.valueOf(rs.getString("kind")),
                rs.getString("added_by"),
                rs.getTimestamp("added_at").toInstant(),
                rs.getString("reason")))
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
            RETURNING host, kind, added_by, added_at, reason
            """)
        .param("host", host)
        .query((rs, i) ->
            new DiscoveryHost(
                rs.getString("host"),
                HostKind.valueOf(rs.getString("kind")),
                rs.getString("added_by"),
                rs.getTimestamp("added_at").toInstant(),
                rs.getString("reason")))
        .optional()
        .orElse(null);
  }
}
