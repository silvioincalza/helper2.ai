package ai.helper2.catalog;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Human review queue: anything that must not be shown as verified without a person deciding. Items are
 * opened here; a person decides them through the ResolveReviewItem use case.
 */
@Service
public class ReviewQueue {

    public enum Kind { ENRICHMENT_VALIDATION, SOURCE_CONFLICT, RELATIONSHIP_CANDIDATE, USER_REPORT }

    public enum Status { OPEN, ACCEPTED, REJECTED }

    public record Item(UUID id, Kind kind, Status status, UUID resourceId, String resourceTitle, String reason,
                       String details, Instant createdAt, Instant resolvedAt, String resolvedBy) {
    }

    public record Links(UUID enrichmentRunId, UUID relationshipId, UUID reportId) {
        public static final Links NONE = new Links(null, null, null);
    }

    private final JdbcClient jdbc;

    public ReviewQueue(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public UUID open(Kind kind, UUID resourceId, String reason, String details, Links links) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO review_item (id, kind, resource_id, enrichment_run_id, relationship_id, report_id, reason, details)
                VALUES (:id, :kind, :rid, :run, :rel, :rep, :reason, :details)
                """)
            .param("id", id).param("kind", kind.name()).param("rid", resourceId)
            .param("run", links.enrichmentRunId()).param("rel", links.relationshipId()).param("rep", links.reportId())
            .param("reason", reason).param("details", details)
            .update();
        return id;
    }

    private static final String SELECT_ITEMS =
        "SELECT i.*, r.title AS resource_title FROM review_item i LEFT JOIN resource r ON r.id = i.resource_id";

    public List<Item> list(Status status) {
        return jdbc.sql(SELECT_ITEMS + """
                 WHERE (CAST(:status AS text) IS NULL OR i.status = :status)
                ORDER BY i.created_at DESC LIMIT 200
                """)
            .param("status", status == null ? null : status.name())
            .query(ReviewQueue::item)
            .list();
    }

    public Optional<Item> find(UUID id) {
        return jdbc.sql(SELECT_ITEMS + " WHERE i.id = :id").param("id", id).query(ReviewQueue::item).optional();
    }

    private static Item item(ResultSet rs, int n) throws SQLException {
        return new Item(
            rs.getObject("id", UUID.class), Kind.valueOf(rs.getString("kind")), Status.valueOf(rs.getString("status")),
            rs.getObject("resource_id", UUID.class), rs.getString("resource_title"), rs.getString("reason"),
            rs.getString("details"), rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("resolved_at") == null ? null : rs.getTimestamp("resolved_at").toInstant(),
            rs.getString("resolved_by"));
    }
}
