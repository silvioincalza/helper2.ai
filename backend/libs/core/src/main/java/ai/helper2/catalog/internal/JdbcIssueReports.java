package ai.helper2.catalog.internal;

import ai.helper2.catalog.IssueReports;
import ai.helper2.catalog.ReviewQueue;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
class JdbcIssueReports implements IssueReports {

  private final JdbcClient jdbc;
  private final ReviewQueue reviewQueue;

  JdbcIssueReports(JdbcClient jdbc, ReviewQueue reviewQueue) {
    this.jdbc = jdbc;
    this.reviewQueue = reviewQueue;
  }

  @Override
  @Transactional
  public Optional<UUID> report(
      UUID resourceId,
      String kind,
      @Nullable String field,
      String message,
      String reason,
      String note) {
    boolean exists =
        jdbc.sql("SELECT EXISTS (SELECT 1 FROM resource WHERE id = :id)")
            .param("id", resourceId)
            .query(Boolean.class)
            .single();
    if (!exists) {
      return Optional.empty();
    }
    UUID id = UUID.randomUUID();
    jdbc.sql(
            """
            INSERT INTO issue_report (id, resource_id, kind, field, message)
            VALUES (:id, :rid, :kind, :field, :message)
            """)
        .param("id", id)
        .param("rid", resourceId)
        .param("kind", kind)
        .param("field", field)
        .param("message", message)
        .update();
    reviewQueue.open(
        ReviewQueue.Kind.USER_REPORT,
        resourceId,
        reason,
        note,
        new ReviewQueue.Links(null, null, id));
    return Optional.of(id);
  }
}
