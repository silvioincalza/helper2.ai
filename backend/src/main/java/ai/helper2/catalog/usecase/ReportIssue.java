package ai.helper2.catalog.usecase;

import ai.helper2.catalog.ReviewQueue;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** A user reports "this is wrong or outdated". Never change data directly; they open a review item. */
@Service
public class ReportIssue {

    public enum Kind { WRONG, OUTDATED, BROKEN_LINK, OTHER }

    private final JdbcClient jdbc;
    private final ReviewQueue reviewQueue;

    public ReportIssue(JdbcClient jdbc, ReviewQueue reviewQueue) {
        this.jdbc = jdbc;
        this.reviewQueue = reviewQueue;
    }

    /** Returns empty when the resource does not exist. */
    @Transactional
    public Optional<UUID> submit(UUID resourceId, Kind kind, String field, String message, UUID reporterUserId) {
        boolean exists = jdbc.sql("SELECT EXISTS (SELECT 1 FROM resource WHERE id = :id)")
            .param("id", resourceId).query(Boolean.class).single();
        if (!exists) {
            return Optional.empty();
        }
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO issue_report (id, resource_id, kind, field, message, reporter_user_id)
                VALUES (:id, :rid, :kind, :field, :message, :user)
                """)
            .param("id", id).param("rid", resourceId).param("kind", kind.name()).param("field", field)
            .param("message", message).param("user", reporterUserId)
            .update();
        reviewQueue.open(ReviewQueue.Kind.USER_REPORT, resourceId, "User report: " + kind.name().toLowerCase(),
            (field == null ? "" : "[" + field + "] ") + message, new ReviewQueue.Links(null, null, id));
        return Optional.of(id);
    }
}
