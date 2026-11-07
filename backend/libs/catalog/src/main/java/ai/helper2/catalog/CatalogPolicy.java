package ai.helper2.catalog;

import java.time.Duration;

/**
 * How long the catalog keeps what people gave it or typed, and what it fetched, as the Privacy page
 * states it (GET /api/v1/policy, ADR 0029).
 *
 * @param handledReports a report after its review was resolved
 * @param queryVectors the vector of a search, shared by the replicas
 * @param webNews an article or news item after it was last retrieved
 * @param pendingReviewPages a page waiting for review, after the decision
 * @param listIndex an awesome list's index rows after the list was last read (ADR 0010)
 * @param errorLogChars how much of a provider's error body one log line keeps
 */
public record CatalogPolicy(
    Duration handledReports,
    Duration queryVectors,
    Duration webNews,
    Duration pendingReviewPages,
    Duration listIndex,
    int errorLogChars) {}
