package ai.helper2.identity.internal;

import ai.helper2.identity.AuditLog;
import ai.helper2.identity.AuditTrail;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The admin_audit table: rows are inserted and, past their retention, deleted; never updated. */
@Repository
class JdbcAuditLog implements AuditTrail, AuditLog {

  private final JdbcClient jdbc;
  private final Clock clock;

  JdbcAuditLog(JdbcClient jdbc, Clock clock) {
    this.jdbc = jdbc;
    this.clock = clock;
  }

  @Override
  public void record(String adminId, Action action, @Nullable String targetId, String outcome) {
    record(adminId, action, targetId, outcome, new Change(null, null, null));
  }

  @Override
  public void record(
      String adminId, Action action, @Nullable String targetId, String outcome, Change change) {
    jdbc.sql(
            """
            INSERT INTO admin_audit (id, admin_id, action, target_id, at, outcome, before_value, after_value, reason)
            VALUES (:id, :admin, :action, :target, :at, :outcome, :before, :after, :reason)
            """)
        .param("id", UUID.randomUUID())
        .param("admin", adminId)
        .param("action", action.name())
        .param("target", targetId)
        .param("at", Timestamp.from(clock.instant()))
        .param("outcome", outcome)
        .param("before", change.before())
        .param("after", change.after())
        .param("reason", change.reason())
        .update();
  }

  @Override
  public Page page(int page, int size) {
    List<Entry> items =
        jdbc.sql("SELECT * FROM admin_audit ORDER BY at DESC, id LIMIT :size OFFSET :offset")
            .param("size", size)
            .param("offset", (long) page * size)
            .query((rs, n) ->
                new Entry(
                    rs.getObject("id", UUID.class),
                    rs.getString("admin_id"),
                    Action.valueOf(rs.getString("action")),
                    rs.getString("target_id"),
                    rs.getTimestamp("at").toInstant(),
                    rs.getString("outcome"),
                    rs.getString("before_value"),
                    rs.getString("after_value"),
                    rs.getString("reason")))
            .list();
    long total = jdbc.sql("SELECT count(*) FROM admin_audit").query(Long.class).single();
    return new Page(items, total);
  }

  @Override
  public int deleteBefore(Instant cutoff) {
    return jdbc.sql("DELETE FROM admin_audit WHERE at < :cutoff")
        .param("cutoff", Timestamp.from(cutoff))
        .update();
  }
}
