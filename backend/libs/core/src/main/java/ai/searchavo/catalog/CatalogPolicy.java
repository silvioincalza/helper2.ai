package ai.searchavo.catalog;

import java.time.Duration;

public record CatalogPolicy(
    Duration handledReports,
    Duration queryVectors,
    Duration webNews,
    Duration pendingReviewPages,
    Duration pastEvents,
    int errorLogChars) {}
