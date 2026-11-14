package ai.helper2.catalog;

import java.time.Duration;

public record CatalogPolicy(
    Duration handledReports,
    Duration queryVectors,
    Duration webNews,
    Duration pendingReviewPages,
    Duration listIndex,
    int errorLogChars) {}
