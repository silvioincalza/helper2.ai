package ai.helper2.catalog.internal;

import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Users' reports that a resource is wrong or outdated, as they were sent. */
@Component
public class IssueReports {

    private final JdbcClient jdbc;

    IssueReports(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Stores the report and returns its id; empty when the resource does not exist. */
    public Optional<UUID> add(UUID resourceId, String kind, @Nullable String field, String message, @Nullable UUID reporterUserId) {
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
            .param("id", id).param("rid", resourceId).param("kind", kind).param("field", field)
            .param("message", message).param("user", reporterUserId)
            .update();
        return Optional.of(id);
    }
}
