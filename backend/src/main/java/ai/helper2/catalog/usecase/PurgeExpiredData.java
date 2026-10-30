package ai.helper2.catalog.usecase;

import ai.helper2.catalog.internal.CatalogRetention;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * The catalog's retention rules (ADR 0034), each with its own period (helper2.retention.*). Review items and issue
 * reports go first, so a resource they alone kept is deleted in the same purge.
 */
@Service("catalogPurgeExpiredData")
public class PurgeExpiredData {

    private static final Logger log = LoggerFactory.getLogger(PurgeExpiredData.class);

    /** What one purge deleted, per rule. */
    public record Purged(int reviewItems, int issueReports, int enrichmentRuns, int webNewsRecords, int pastEvents,
                         int observedResources) {
    }

    private final CatalogRetention retention;
    private final Clock clock;
    private final Duration resolvedReviewItems;
    private final Duration resolvedIssueReports;
    private final Duration enrichmentRuns;
    private final Duration webNews;
    private final Duration pastEvents;
    private final Duration observedUnseen;

    public PurgeExpiredData(CatalogRetention retention, Clock clock,
                            @Value("${helper2.retention.resolved-review-items}") Duration resolvedReviewItems,
                            @Value("${helper2.retention.resolved-issue-reports}") Duration resolvedIssueReports,
                            @Value("${helper2.retention.enrichment-runs}") Duration enrichmentRuns,
                            @Value("${helper2.retention.web-news}") Duration webNews,
                            @Value("${helper2.retention.past-events}") Duration pastEvents,
                            @Value("${helper2.retention.observed-unseen}") Duration observedUnseen) {
        this.retention = retention;
        this.clock = clock;
        this.resolvedReviewItems = resolvedReviewItems;
        this.resolvedIssueReports = resolvedIssueReports;
        this.enrichmentRuns = enrichmentRuns;
        this.webNews = webNews;
        this.pastEvents = pastEvents;
        this.observedUnseen = observedUnseen;
    }

    public Purged purge() {
        Instant now = clock.instant();
        Purged purged = new Purged(
            retention.deleteReviewItemsResolvedBefore(now.minus(resolvedReviewItems)),
            retention.deleteIssueReportsResolvedBefore(now.minus(resolvedIssueReports)),
            retention.deleteEnrichmentRunsStartedBefore(now.minus(enrichmentRuns)),
            retention.deleteWebNewsRetrievedBefore(now.minus(webNews)),
            retention.deleteEventsEndedBefore(now.minus(pastEvents)),
            retention.deleteObservedUnseenSince(now.minus(observedUnseen)));
        log.info("catalog retention: {}", purged);
        return purged;
    }
}
