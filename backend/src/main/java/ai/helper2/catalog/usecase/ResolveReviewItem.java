package ai.helper2.catalog.usecase;

import ai.helper2.catalog.ReviewQueue;
import ai.helper2.platform.BadRequestException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** An administrator decides an open review item. */
@Service
public class ResolveReviewItem {

    private final JdbcClient jdbc;
    private final ReviewQueue queue;

    public ResolveReviewItem(JdbcClient jdbc, ReviewQueue queue) {
        this.jdbc = jdbc;
        this.queue = queue;
    }

    /**
     * Resolving a relationship candidate is the ONLY way an AI-proposed relationship becomes VERIFIED;
     * rejecting it marks it REJECTED. Other kinds just record the decision.
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
        return queue.find(id);
    }
}
