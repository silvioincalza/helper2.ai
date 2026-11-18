package ai.helper2.research;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public record ResearchRun(
    UUID id,
    UUID itemId,
    Status status,
    String model,
    String requestedBy,
    Instant requestedAt,
    @Nullable Instant startedAt,
    @Nullable Instant finishedAt,
    int steps,
    int promptTokens,
    int completionTokens,
    @Nullable ResearchResult result,
    @Nullable String error) {

  public enum Status {
    QUEUED,
    RUNNING,
    DONE,
    FAILED,
    CANCELLED;

    public boolean active() {
      return this == QUEUED || this == RUNNING;
    }
  }
}
