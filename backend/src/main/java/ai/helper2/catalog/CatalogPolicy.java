package ai.helper2.catalog;

import java.time.Duration;

/**
 * How long the catalog keeps what people gave it or typed, as the Privacy page states it (GET
 * /api/v1/policy, ADR 0052).
 *
 * @param handledReports a report after its review was resolved
 * @param queryVectors the vector of a search, shared by the replicas
 */
public record CatalogPolicy(Duration handledReports, Duration queryVectors) {}
