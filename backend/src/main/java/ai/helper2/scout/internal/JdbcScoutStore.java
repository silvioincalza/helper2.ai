package ai.helper2.scout.internal;

import ai.helper2.scout.RunStatus;
import ai.helper2.scout.ScoutCursor;
import ai.helper2.scout.RunSummary;
import ai.helper2.scout.ScheduledRunStore;
import ai.helper2.scout.ScoutStore;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Component
class JdbcScoutStore implements ScoutStore, ScheduledRunStore {

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    JdbcScoutStore(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public ScoutCursor cursor(String sourceId) {
        return jdbc.sql("SELECT cursor FROM source_cursor WHERE source_id = :id").param("id", sourceId)
            .query(String.class).optional().map(raw -> {
                JsonNode n = json.readTree(raw);
                return new ScoutCursor(text(n, "checkpoint"), text(n, "resume"), text(n, "passStartedAt"));
            }).orElse(null);
    }

    @Override
    public void saveCursor(String sourceId, ScoutCursor cursor) {
        ObjectNode n = json.createObjectNode();
        n.put("checkpoint", cursor.getCheckpoint());
        n.put("resume", cursor.getResumeCursor());
        n.put("passStartedAt", cursor.getPassStartedAt());
        jdbc.sql("""
                INSERT INTO source_cursor (source_id, cursor) VALUES (:id, :c)
                ON CONFLICT (source_id) DO UPDATE SET cursor = EXCLUDED.cursor, updated_at = now()
                """).param("id", sourceId).param("c", json.writeValueAsString(n)).update();
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.path(field);
        return v.isMissingNode() || v.isNull() ? null : v.asString();
    }

    @Override
    public void save(RunSummary run) {
        jdbc.sql("""
                INSERT INTO scout_run (id, source_id, status, started_at, finished_at, items_seen, items_changed, enrichments_queued, message)
                VALUES (:id, :src, :status, :started, :finished, :seen, :changed, :queued, :message)
                ON CONFLICT (id) DO UPDATE SET status = EXCLUDED.status, finished_at = EXCLUDED.finished_at,
                    items_seen = EXCLUDED.items_seen, items_changed = EXCLUDED.items_changed,
                    enrichments_queued = EXCLUDED.enrichments_queued, message = EXCLUDED.message
                """)
            .param("id", run.getId()).param("src", run.getSourceId()).param("status", run.getStatus().name())
            .param("started", Timestamp.from(run.getStartedAt()))
            .param("finished", run.getFinishedAt() == null ? null : Timestamp.from(run.getFinishedAt()))
            .param("seen", run.getItemsSeen()).param("changed", run.getItemsChanged())
            .param("queued", run.getEnrichmentsQueued()).param("message", run.getMessage())
            .update();
        // A run in progress keeps the last finished outcome: the Sources page shows that one.
        String outcome = switch (run.getStatus()) {
            case SUCCEEDED -> "SUCCEEDED";
            case FAILED -> "FAILED";
            case SKIPPED -> "SKIPPED";
            case RUNNING -> null;
        };
        if (outcome != null) {
            jdbc.sql("UPDATE source SET last_outcome = :o, last_checked_at = now() WHERE id = :id")
                .param("o", outcome).param("id", run.getSourceId()).update();
        }
    }

    @Override
    public List<RunSummary> recent(int limit) {
        return jdbc.sql("SELECT * FROM scout_run ORDER BY started_at DESC LIMIT :n").param("n", limit)
            .query((rs, i) -> new RunSummary(rs.getObject("id", UUID.class), rs.getString("source_id"),
                RunStatus.valueOf(rs.getString("status")), rs.getTimestamp("started_at").toInstant(),
                rs.getTimestamp("finished_at") == null ? null : rs.getTimestamp("finished_at").toInstant(),
                rs.getInt("items_seen"), rs.getInt("items_changed"), rs.getInt("enrichments_queued"), rs.getString("message")))
            .list();
    }

    /** Like save(FAILED) for each record: the source's last outcome becomes FAILED too. */
    @Override
    public int markInterrupted(Instant at) {
        return jdbc.sql("""
                WITH closed AS (
                    UPDATE scout_run SET status = 'FAILED', finished_at = :at,
                        message = 'Interrupted: the replica running it stopped'
                    WHERE status = 'RUNNING'
                    RETURNING source_id
                ), sources AS (
                    UPDATE source SET last_outcome = 'FAILED', last_checked_at = now()
                    WHERE id IN (SELECT source_id FROM closed)
                )
                SELECT count(*) FROM closed
                """).param("at", Timestamp.from(at)).query(Integer.class).single();
    }

    @Override
    public Instant lastScheduledRunEnd() {
        return jdbc.sql("SELECT last_ended_at FROM scout_schedule").query(Timestamp.class).optional()
            .map(Timestamp::toInstant).orElse(null);
    }

    @Override
    public void scheduledRunEnded(Instant at) {
        jdbc.sql("""
                INSERT INTO scout_schedule (last_ended_at) VALUES (:at)
                ON CONFLICT (id) DO UPDATE SET last_ended_at = EXCLUDED.last_ended_at
                """).param("at", Timestamp.from(at)).update();
    }
}
