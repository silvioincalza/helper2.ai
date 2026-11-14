package ai.helper2.catalog;

import ai.helper2.shared.ValueOrigin;
import ai.helper2.shared.Verification;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public interface ReviewEvidence {
  int MAX_QUOTES = 5;

  int MAX_FIELDS = 50;

  Evidence of(UUID itemId);

  List<String> conflictingFields(UUID itemId);

  record Evidence(
      @Nullable Run run,
      List<String> validationErrors,
      List<Field> fields,
      @Nullable Relationship relationship) {

    public static final Evidence NONE = new Evidence(null, List.of(), List.of(), null);

    public boolean aiWritten() {
      return !fields.isEmpty() || relationship != null || !validationErrors.isEmpty();
    }
  }

  record Run(
      UUID id,
      String provider,
      String model,
      String promptVersion,
      String outcome,
      Instant startedAt,
      @Nullable Instant finishedAt,
      @Nullable String extraEvidence) {}

  record Field(
      String field,
      String value,
      ValueOrigin origin,
      Verification verification,
      List<Quote> quotes) {}

  record Relationship(
      String kind,
      String target,
      @Nullable UUID targetId,
      Verification verification,
      ValueOrigin origin,
      boolean targetCurated,
      List<Quote> quotes) {}

  record Quote(
      @Nullable String excerpt, String sourceUrl, @Nullable String sourceId, Instant retrievedAt) {}
}
