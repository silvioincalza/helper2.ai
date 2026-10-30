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

    public enum Kind {
        ENRICHMENT_VALIDATION, SOURCE_CONFLICT, RELATIONSHIP_CANDIDATE, USER_REPORT,
        /** A page a collector proposes for the catalog (ADR 0032 §8): no resource yet, only its URL. */
        COURSE_CANDIDATE
    }

    /** SUPERSEDED: a newer enrichment run of the same resource replaced the run the item was about. */
    public enum Status { OPEN, ACCEPTED, REJECTED, SUPERSEDED }

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

    /**
     * Closes the resource's OPEN items about earlier enrichment runs as SUPERSEDED by [runId]: a person decides
     * only what the newest run says.
     */
    public void supersedeEnrichmentItems(UUID resourceId, UUID runId) {
        jdbc.sql("""
                UPDATE review_item SET status = 'SUPERSEDED', resolved_at = now(), resolution_note = :note
                WHERE resource_id = :rid AND status = 'OPEN' AND enrichment_run_id IS NOT NULL
                  AND enrichment_run_id <> :run
                """)
            .param("rid", resourceId).param("run", runId).param("note", "Superseded by enrichment run " + runId)
            .update();
    }

    /**
     * Opens a candidate item for [canonicalUrl] unless the catalog already has that resource or the URL was ever
     * proposed before (whatever was decided). Returns whether an item was opened.
     */
    public boolean proposeCandidate(Kind kind, String canonicalUrl, String reason, String details) {
        return jdbc.sql("""
                INSERT INTO review_item (id, kind, candidate_url, reason, details)
                SELECT :id, :kind, :url, :reason, :details
                WHERE NOT EXISTS (SELECT 1 FROM resource WHERE canonical_url = :url)
                ON CONFLICT (kind, candidate_url) WHERE candidate_url IS NOT NULL DO NOTHING
                """)
            .param("id", UUID.randomUUID()).param("kind", kind.name()).param("url", canonicalUrl)
            .param("reason", reason).param("details", details)
            .update() == 1;
    }

    private static final String SELECT_ITEMS =
        "SELECT i.*, r.title AS resource_title FROM review_item i LEFT JOIN resource r ON r.id = i.resource_id";

    /** One page of items: [items] in the page, [total] across every page. */
    public record Page(List<Item> items, long total) {
    }

    /** Newest first; all statuses when [status] is null. */
    public Page list(Status status, int page, int size) {
        String wanted = status == null ? null : status.name();
        List<Item> items = jdbc.sql(SELECT_ITEMS + """
                 WHERE (CAST(:status AS text) IS NULL OR i.status = :status)
                ORDER BY i.created_at DESC, i.id
                LIMIT :size OFFSET :offset
                """)
            .param("status", wanted).param("size", size).param("offset", (long) page * size)
            .query(ReviewQueue::item)
            .list();
        long total = jdbc.sql("SELECT count(*) FROM review_item WHERE (CAST(:status AS text) IS NULL OR status = :status)")
            .param("status", wanted).query(Long.class).single();
        return new Page(items, total);
    }

    /** Items waiting for a person; for the review backlog gauge. */
    public long openCount() {
        return jdbc.sql("SELECT count(*) FROM review_item WHERE status = 'OPEN'").query(Long.class).single();
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
