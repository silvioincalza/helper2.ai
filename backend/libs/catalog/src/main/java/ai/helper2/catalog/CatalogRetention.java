package ai.helper2.catalog;

import java.time.Instant;

public interface CatalogRetention {
  int deleteObservedUnseenSince(Instant cutoff);

  int deleteWebNewsRetrievedBefore(Instant cutoff);

  int deleteListIndexUpdatedBefore(Instant cutoff);

  int deleteEventsEndedBefore(Instant cutoff);

  int deleteEnrichmentRunsStartedBefore(Instant cutoff);

  int deleteReviewItemsResolvedBefore(Instant cutoff);

  int deleteIssueReportsResolvedBefore(Instant cutoff);
}
