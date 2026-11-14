package ai.helper2.catalog.enrichment;

import ai.helper2.catalog.KnownTarget;
import ai.helper2.shared.ValueOrigin;
import ai.helper2.shared.Verification;
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
      boolean needsReview) {
    public Run(
        UUID resourceId,
        String provider,
        String model,
        String promptVersion,
        String inputFingerprint,
        Instant startedAt,
        String outcome,
        List<String> errors,
        @Nullable String extraEvidence) {
      this(
          resourceId,
          provider,
          model,
          promptVersion,
          inputFingerprint,
          startedAt,
          outcome,
          errors,
          extraEvidence,
          !errors.isEmpty());
    }
  }

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
      ValueOrigin origin) {
    public Field(String field, String value, Verification verification, List<Citation> citations) {
      this(field, value, verification, citations, ValueOrigin.AI_EXTRACTED);
    }
  }

  record Relationship(
      String kind,
      String target,
      @Nullable UUID targetId,
      Verification verification,
      List<Citation> citations,
      ValueOrigin origin,
      KnownTarget.@Nullable Kind knownKind,
      @Nullable String statedKind) {
    public Relationship(
        String kind,
        String target,
        @Nullable UUID targetId,
        Verification verification,
        List<Citation> citations,
        ValueOrigin origin,
        KnownTarget.@Nullable Kind knownKind) {
      this(kind, target, targetId, verification, citations, origin, knownKind, null);
    }

    public Relationship(
        String kind,
        String target,
        @Nullable UUID targetId,
        Verification verification,
        List<Citation> citations,
        ValueOrigin origin) {
      this(kind, target, targetId, verification, citations, origin, null);
    }

    public Relationship(
        String kind,
        String target,
        @Nullable UUID targetId,
        Verification verification,
        List<Citation> citations) {
      this(kind, target, targetId, verification, citations, ValueOrigin.AI_EXTRACTED);
    }
  }

  record Claim(String text, List<Citation> citations, boolean selfDeclared) {
    public Claim(String text, List<Citation> citations) {
      this(text, citations, false);
    }
  }

  record Checked(List<Field> fields, List<Relationship> relationships, List<Claim> summary) {}

  UUID record(Run run, @Nullable Checked checked);
}
