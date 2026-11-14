package ai.helper2.catalog.usecase;

import ai.helper2.catalog.CatalogRetention;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PurgeExpiredData {

  private static final Logger log = LoggerFactory.getLogger(PurgeExpiredData.class);

  public record Purged(
      int reviewItems,
      int issueReports,
      int enrichmentRuns,
      int webNewsRecords,
      int pastEvents,
      int observedResources,
      int listIndexRows) {}

  public record Periods(
      Duration resolvedReviewItems,
      Duration resolvedIssueReports,
      Duration enrichmentRuns,
      Duration webNews,
      Duration pastEvents,
      Duration observedUnseen,
      Duration listIndex) {}

  private final CatalogRetention retention;
  private final Clock clock;
  private final Periods periods;

  public PurgeExpiredData(CatalogRetention retention, Clock clock, Periods periods) {
    this.retention = retention;
    this.clock = clock;
    this.periods = periods;
  }

  public Purged purge() {
    Instant now = clock.instant();
    Purged purged =
        new Purged(
            retention.deleteReviewItemsResolvedBefore(now.minus(periods.resolvedReviewItems())),
            retention.deleteIssueReportsResolvedBefore(now.minus(periods.resolvedIssueReports())),
            retention.deleteEnrichmentRunsStartedBefore(now.minus(periods.enrichmentRuns())),
            retention.deleteWebNewsRetrievedBefore(now.minus(periods.webNews())),
            retention.deleteEventsEndedBefore(now.minus(periods.pastEvents())),
            retention.deleteObservedUnseenSince(now.minus(periods.observedUnseen())),
            retention.deleteListIndexUpdatedBefore(now.minus(periods.listIndex())));
    log.info("catalog retention: {}", purged);
    return purged;
  }
}
