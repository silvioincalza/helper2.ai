package ai.helper2.catalog.internal;

import ai.helper2.catalog.ReviewQueue;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** A person's decision on an open review item, and what it means for the relationship the item is about. */
@Component
public class ReviewDecisions {

    private final JdbcClient jdbc;

    ReviewDecisions(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Decides the item when it is still OPEN; false otherwise. A relationship candidate becomes VERIFIED when
     * accepted and REJECTED when rejected: the only way an AI-proposed relationship is verified.
     */
    public boolean decide(UUID itemId, ReviewQueue.Status decision, String by, String note) {
        int updated = jdbc.sql("""
                UPDATE review_item SET status = :s, resolved_at = now(), resolved_by = :by, resolution_note = :note
                WHERE id = :id AND status = 'OPEN'
                """)
            .param("s", decision.name()).param("by", by).param("note", note).param("id", itemId).update();
        if (updated == 1) {
            jdbc.sql("""
                    UPDATE relationship SET verification = :v
                    WHERE id = (SELECT relationship_id FROM review_item WHERE id = :id)
                    """)
                .param("v", decision == ReviewQueue.Status.ACCEPTED ? "VERIFIED" : "REJECTED").param("id", itemId).update();
        }
        return updated == 1;
    }
}
