package ai.helper2.catalog.usecase;

import ai.helper2.catalog.ReviewQueue;
import ai.helper2.identity.AuditTrail;
import ai.helper2.platform.BadRequestException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** An administrator decides an open review item; the decision is recorded in the audit trail. */
@Service
public class ResolveReviewItem {

    private final JdbcClient jdbc;
    private final ReviewQueue queue;
    private final AuditTrail audit;

    public ResolveReviewItem(JdbcClient jdbc, ReviewQueue queue, AuditTrail audit) {
        this.jdbc = jdbc;
        this.queue = queue;
        this.audit = audit;
    }

    /**
     * Resolving a relationship candidate is the ONLY way an AI-proposed relationship becomes VERIFIED;
     * rejecting it marks it REJECTED. Other kinds just record the decision. [by] is the administrator's provider
     * subject; the audit entry's outcome is the decision, NOT_OPEN for an item already decided, NOT_FOUND for none.
     */
    @Transactional
    public Optional<ReviewQueue.Item> resolve(UUID id, ReviewQueue.Status decision, String by, String note) {
        if (decision != ReviewQueue.Status.ACCEPTED && decision != ReviewQueue.Status.REJECTED) {
            throw new BadRequestException("decision must be ACCEPTED or REJECTED");
        }
        int updated = jdbc.sql("""
                UPDATE review_item SET status = :s, resolved_at = now(), resolved_by = :by, resolution_note = :note
                WHERE id = :id AND status = 'OPEN'
                """)
            .param("s", decision.name()).param("by", by).param("note", note).param("id", id).update();
        if (updated == 1) {
            jdbc.sql("""
                    UPDATE relationship SET verification = :v
                    WHERE id = (SELECT relationship_id FROM review_item WHERE id = :id)
                    """)
                .param("v", decision == ReviewQueue.Status.ACCEPTED ? "VERIFIED" : "REJECTED").param("id", id).update();
        }
        Optional<ReviewQueue.Item> item = queue.find(id);
        String outcome = updated == 1 ? decision.name() : item.isPresent() ? "NOT_OPEN" : "NOT_FOUND";
        audit.record(by, AuditTrail.Action.RESOLVE_REVIEW_ITEM, id.toString(), outcome);
        return item;
    }
}
