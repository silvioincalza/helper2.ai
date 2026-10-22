package ai.helper2.catalog;

import ai.helper2.search.MergedResult;
import ai.helper2.shared.ValueOrigin;
import ai.helper2.shared.Verification;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A resource for its detail page: every fact with its origin, verification and evidence. */
public record ResourceDetail(MergedResult card, List<Assertion> assertions, List<Relationship> relationships,
                             Summary summary, List<SourceRecordInfo> sourceRecords, String enrichmentStatus) {

    public record Evidence(UUID id, URI sourceUrl, Instant retrievedAt, String excerpt) {
    }

    public record Assertion(String field, String value, ValueOrigin origin, Verification verification,
                            String sourceId, List<Evidence> evidence) {
    }

    public record Relationship(String kind, UUID targetId, String targetTitle, Verification verification,
                               List<Evidence> evidence) {
    }

    public record SummaryClaim(String text, List<Evidence> evidence) {
    }

    public record Summary(List<SummaryClaim> claims, String model, String promptVersion, Instant generatedAt) {
    }

    public record SourceRecordInfo(String sourceId, String externalId, URI url, Instant retrievedAt, String fingerprint) {
    }
}
