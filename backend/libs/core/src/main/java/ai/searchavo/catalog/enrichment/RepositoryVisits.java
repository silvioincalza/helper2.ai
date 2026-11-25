package ai.searchavo.catalog.enrichment;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public interface RepositoryVisits {

  List<Due> due(int limit);

  void record(UUID recordId, Visit visit, Instant visitedAt);

  record Due(UUID recordId, String repository) {}

  sealed interface Visit {}

  /**
   * What GitHub states of a repository it found. {@code stars} is null when the answer carries no
   * count: no count is never zero stars.
   */
  record Found(
      boolean archived,
      @Nullable Parent parent,
      @Nullable Instant lastReleaseAt,
      @Nullable Long stars)
      implements Visit {}

  record Parent(String repository, boolean archived) {}

  record Gone() implements Visit {}

  record Unanswered() implements Visit {}
}
