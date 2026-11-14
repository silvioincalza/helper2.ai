package ai.helper2.catalog;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public interface ReviewQueue {

  enum Kind {
    ENRICHMENT_VALIDATION,
    RELATIONSHIP_CANDIDATE,
    USER_REPORT,
    COURSE_CANDIDATE,
    RESOURCE_CANDIDATE,
    EXAMPLE_CANDIDATE,
    KNOWN_TARGET_CANDIDATE;

    public boolean detailsAreJson() {
      return this == RESOURCE_CANDIDATE
          || this == EXAMPLE_CANDIDATE
          || this == KNOWN_TARGET_CANDIDATE;
    }
  }

  enum Status {
    OPEN,
    ACCEPTED,
    REJECTED,
    SUPERSEDED
  }

  record Item(
      UUID id,
      Kind kind,
      Status status,
      UUID resourceId,
      String resourceTitle,
      String reason,
      String details,
      Instant createdAt,
      Instant resolvedAt,
      String resolvedBy,
      List<String> categories) {}

  record Links(UUID enrichmentRunId, UUID relationshipId, UUID reportId) {
    public static final Links NONE = new Links(null, null, null);
  }

  record Page(List<Item> items, long total) {}

  UUID open(Kind kind, UUID resourceId, String reason, String details, Links links);

  void supersedeEnrichmentItems(UUID resourceId, UUID runId);

  boolean proposeCandidate(Kind kind, String canonicalUrl, String reason, String details);

  Optional<Item> openCandidate(Kind kind, String canonicalUrl);

  boolean updateCandidate(UUID id, @Nullable String oldDetails, String reason, String details);

  int candidatesOpenedToday(Kind kind, String sourceId);

  record Listed(UUID resourceId, String title) {}

  Optional<Listed> listedAs(String canonicalUrl);

  boolean repositoryListed(String repositoryUrl);

  boolean candidateKnown(Kind kind, String canonicalUrl);

  List<Item> openItems(Kind kind);

  Page list(Status status, Kind kind, int page, int size);

  Page list(Status status, Kind kind, String category, int page, int size);

  Map<Kind, Long> openCountsByKind();

  long openCount();

  void lock(UUID id);

  Optional<Item> find(UUID id);
}
