package ai.searchavo.admin.usecase;

import ai.searchavo.catalog.ReviewQueue;
import ai.searchavo.examples.WaitingDraft;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public record ReviewItemView(
    UUID id,
    ReviewQueue.Kind kind,
    Status status,
    @Nullable UUID resourceId,
    @Nullable String resourceTitle,
    String reason,
    @Nullable ReviewDetails details,
    @Nullable WaitingDraft draft,
    Instant createdAt,
    @Nullable Instant resolvedAt,
    @Nullable String resolvedBy,
    List<String> categories) {

  public enum Status {
    OPEN,
    ACCEPTED,
    REJECTED,
    SUPERSEDED;

    static Status of(ReviewQueue.Status status) {
      return valueOf(status.name());
    }

    ReviewQueue.Status inQueue() {
      return ReviewQueue.Status.valueOf(name());
    }
  }
}
