package ai.helper2.catalog;

import ai.helper2.search.MergedResult;
import ai.helper2.shared.CanonicalUrl;
import ai.helper2.shared.ValueOrigin;
import ai.helper2.shared.Verification;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public record ResourceDetail(
    MergedResult card,
    List<Assertion> assertions,
    List<Relationship> relationships,
    Summary summary,
    List<SourceRecordInfo> sourceRecords,
    String enrichmentStatus) {

  public record Evidence(UUID id, URI sourceUrl, Instant retrievedAt, String excerpt) {}

  public record Assertion(
      String field,
      String value,
      ValueOrigin origin,
      Verification verification,
      String sourceId,
      List<Evidence> evidence,
      @Nullable String model) {
    public Assertion(
        String field,
        String value,
        ValueOrigin origin,
        Verification verification,
        String sourceId,
        List<Evidence> evidence) {
      this(field, value, origin, verification, sourceId, evidence, null);
    }
  }

  public record Relationship(
      String kind,
      UUID targetId,
      String targetTitle,
      Verification verification,
      List<Evidence> evidence,
      ValueOrigin origin,
      @Nullable String model) {}

  public record SummaryClaim(String text, List<Evidence> evidence, boolean selfDeclared) {}

  public record Summary(
      List<SummaryClaim> claims, String model, String promptVersion, Instant generatedAt) {}

  public record SourceRecordInfo(
      String sourceId,
      String externalId,
      URI url,
      @Nullable URI repositoryUrl,
      Instant retrievedAt,
      String fingerprint) {

    public static SourceRecordInfo of(RecordRow r) {
      CanonicalUrl repository = r.hit().getRepositoryUrl();
      return new SourceRecordInfo(
          r.sourceId(),
          r.externalId(),
          URI.create(r.sourceUrl()),
          repository == null ? null : URI.create(repository.getValue()),
          r.retrievedAt(),
          r.fingerprint());
    }
  }
}
