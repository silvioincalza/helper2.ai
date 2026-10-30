package ai.helper2.identity.internal;

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
public class AuditLog implements AuditTrail {

    public record Entry(UUID id, String adminId, Action action, @Nullable String targetId, Instant at, String outcome) {
    }

    public record Page(List<Entry> items, long total) {
    }

    private final JdbcClient jdbc;
    private final Clock clock;

    AuditLog(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public void record(String adminId, Action action, @Nullable String targetId, String outcome) {
        jdbc.sql("INSERT INTO admin_audit (id, admin_id, action, target_id, at, outcome) VALUES (:id, :admin, :action, :target, :at, :outcome)")
            .param("id", UUID.randomUUID()).param("admin", adminId).param("action", action.name())
            .param("target", targetId).param("at", Timestamp.from(clock.instant())).param("outcome", outcome)
            .update();
    }

    /** Newest first. */
    public Page page(int page, int size) {
        List<Entry> items = jdbc.sql("SELECT * FROM admin_audit ORDER BY at DESC, id LIMIT :size OFFSET :offset")
            .param("size", size).param("offset", (long) page * size)
            .query((rs, n) -> new Entry(rs.getObject("id", UUID.class), rs.getString("admin_id"),
                Action.valueOf(rs.getString("action")), rs.getString("target_id"), rs.getTimestamp("at").toInstant(),
                rs.getString("outcome")))
            .list();
        long total = jdbc.sql("SELECT count(*) FROM admin_audit").query(Long.class).single();
        return new Page(items, total);
    }

    /** Deletes the entries written before [cutoff]; returns how many. */
    public int deleteBefore(Instant cutoff) {
        return jdbc.sql("DELETE FROM admin_audit WHERE at < :cutoff").param("cutoff", Timestamp.from(cutoff)).update();
    }
}
