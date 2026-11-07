package ai.helper2.catalog.usecase;

import ai.helper2.catalog.config.CatalogRetentionProperties;
import ai.helper2.catalog.internal.CatalogRetention;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The catalog's retention rules (ADR 0035), each with its own period (helper2.retention.*). Review
 * items and issue reports go first, so a resource they alone kept is deleted in the same purge.
 */
@Service("catalogPurgeExpiredData")
public class PurgeExpiredData {

  private static final Logger log = LoggerFactory.getLogger(PurgeExpiredData.class);

  /** What one purge deleted, per rule. */
  public record Purged(
      int reviewItems,
      int issueReports,
      int enrichmentRuns,
      int webNewsRecords,
      int pastEvents,
      int observedResources,
      int listIndexRows) {}

  private final CatalogRetention retention;
  private final Clock clock;
  private final CatalogRetentionProperties periods;

  public PurgeExpiredData(
      CatalogRetention retention, Clock clock, CatalogRetentionProperties periods) {
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
