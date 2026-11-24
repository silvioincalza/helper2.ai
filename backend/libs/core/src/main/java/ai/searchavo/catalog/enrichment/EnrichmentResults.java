package ai.searchavo.catalog.enrichment;

import ai.searchavo.catalog.KnownTarget;
import ai.searchavo.shared.ValueOrigin;
import ai.searchavo.shared.Verification;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public interface EnrichmentResults {
  record Run(
      UUID resourceId,
      String provider,
      String model,
      String promptVersion,
      String inputFingerprint,
      Instant startedAt,
      String outcome,
      List<String> errors,
      @Nullable String extraEvidence,
      boolean needsReview) {}

  record Citation(
      UUID sourceRecordId,
      String excerpt,
      @Nullable URI sourceUrl,
      @Nullable Instant retrievedAt) {}

  record Field(
      String field,
      String value,
      Verification verification,
      List<Citation> citations,
      ValueOrigin origin) {}

  record Relationship(
      String kind,
      String target,
      @Nullable UUID targetId,
      Verification verification,
      List<Citation> citations,
      ValueOrigin origin,
      KnownTarget.@Nullable Kind knownKind,
      @Nullable String statedKind) {}

  record Claim(String text, List<Citation> citations, boolean selfDeclared) {}

  record Checked(List<Field> fields, List<Relationship> relationships, List<Claim> summary) {}

  UUID record(Run run, @Nullable Checked checked);
}
