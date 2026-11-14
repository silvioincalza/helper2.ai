package ai.helper2.catalog.enrichment;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public interface EnrichmentCandidates {
  List<UUID> pending(int limit, int minText, int maxFailed);

  long pendingCount(int minText, int maxFailed);

  @Nullable Instant oldestPending(int minText);

  List<BacklogEntry> backlog(
      View view, @Nullable BacklogCursor after, int size, int minText, int maxFailed);

  @Nullable Standing standing(UUID id, int minText, int maxFailed);

  int prioritisedCount(int minText, int maxFailed);

  enum View {
    WAITING,
    SKIPPED,
    OUT_OF_RETRIES
  }

  enum State {
    PRIORITISED,
    WAITING,
    SKIPPED,
    OUT_OF_RETRIES
  }

  enum Waiting {
    NEW,
    MODEL_UNAVAILABLE,
    RETRY
  }

  record BacklogEntry(
      UUID id,
      String title,
      State state,
      @Nullable Waiting waiting,
      Instant firstDiscoveredAt,
      BigDecimal popularity,
      int failedRuns,
      @Nullable String lastOutcome,
      @Nullable Instant lastRunAt,
      @Nullable Instant skippedAt,
      @Nullable String skipReason) {
    public BacklogCursor cursor() {
      if (state == State.SKIPPED && skippedAt != null) {
        return new BacklogCursor(Part.REST, BigDecimal.ZERO, skippedAt, id);
      }
      if (state != State.WAITING) {
        Part part = state == State.PRIORITISED ? Part.PRIORITISED : Part.REST;
        return new BacklogCursor(part, BigDecimal.ZERO, firstDiscoveredAt, id);
      }
      Part part = waiting == Waiting.NEW ? Part.NEW : Part.REST;
      return new BacklogCursor(part, popularity, firstDiscoveredAt, id);
    }
  }

  enum Part {
    PRIORITISED,
    NEW,
    REST
  }

  record BacklogCursor(Part part, BigDecimal popularity, Instant at, UUID id) {}

  record Standing(boolean due, boolean outOfRetries, boolean skipped, boolean prioritised) {}

  List<UUID> withEnoughText(List<UUID> ids, int minText);

  @Nullable Candidate candidate(UUID id);

  @Nullable String lastJudgedFingerprint(UUID id);

  default void recordArchived(UUID recordId, boolean archived, Instant checkedAt) {}

  Map<String, UUID> titled(Collection<String> lowerTitles, UUID excluding);

  record Candidate(
      String title,
      String type,
      @Nullable Boolean openSource,
      @Nullable String license,
      List<SourceText> sources,
      Map<String, String> curated,
      Map<String, Set<String>> sourceStated) {

    public Candidate(
        String title,
        String type,
        @Nullable Boolean openSource,
        @Nullable String license,
        List<SourceText> sources,
        Map<String, String> curated) {
      this(title, type, openSource, license, sources, curated, Map.of());
    }

    public Candidate(
        String title,
        String type,
        @Nullable Boolean openSource,
        @Nullable String license,
        List<SourceText> sources) {
      this(title, type, openSource, license, sources, Map.of());
    }
  }

  record SourceText(
      UUID recordId,
      String url,
      Instant retrievedAt,
      String text,
      String sourceId,
      String externalId,
      @Nullable String repositoryUrl) {}
}
