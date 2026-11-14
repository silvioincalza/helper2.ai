package ai.helper2.catalog.internal;

import ai.helper2.catalog.ReviewDecisions;
import ai.helper2.catalog.ReviewQueue;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

class JdbcReviewDecisions implements ReviewDecisions {

  private final JdbcClient jdbc;

  JdbcReviewDecisions(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public boolean decide(UUID itemId, ReviewQueue.Status decision, String by, String note) {
    int updated =
        jdbc.sql(
                """
                UPDATE review_item SET status = :s, resolved_at = now(), resolved_by = :by, resolution_note = :note
                WHERE id = :id AND status = 'OPEN'
                """)
            .param("s", decision.name())
            .param("by", by)
            .param("note", note)
            .param("id", itemId)
            .update();
    if (updated == 1) {
      keepCandidateDecision(itemId);
      jdbc.sql(
              """
              UPDATE relationship SET verification = :v
              WHERE id = (SELECT relationship_id FROM review_item WHERE id = :id)
              """)
          .param("v", decision == ReviewQueue.Status.ACCEPTED ? "VERIFIED" : "REJECTED")
          .param("id", itemId)
          .update();
    }
    return updated == 1;
  }

  private void keepCandidateDecision(UUID itemId) {
    jdbc.sql(
            """
            INSERT INTO candidate_decision (kind, candidate_key, status, decided_at)
            SELECT kind, %s, status, resolved_at FROM review_item
            WHERE id = :id AND candidate_url IS NOT NULL
            ON CONFLICT (kind, candidate_key) DO UPDATE
              SET status = EXCLUDED.status, decided_at = EXCLUDED.decided_at
            """
                .formatted(UrlKeys.keyOf("candidate_url")))
        .param("id", itemId)
        .update();
    jdbc.sql(
            """
            INSERT INTO candidate_decision (kind, candidate_key, status, decided_at)
            SELECT kind, %s, status, resolved_at FROM review_item
            WHERE id = :id AND kind = 'KNOWN_TARGET_CANDIDATE'
            ON CONFLICT (kind, candidate_key) DO UPDATE
              SET status = EXCLUDED.status, decided_at = EXCLUDED.decided_at
            """
                .formatted(UrlKeys.keyOf("details ->> 'key'")))
        .param("id", itemId)
        .update();
  }
}
