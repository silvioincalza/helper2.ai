package ai.helper2.scout.internal;

import ai.helper2.catalog.KnownTarget;
import ai.helper2.catalog.KnownTargets;
import ai.helper2.catalog.ScoutPolicy;
import ai.helper2.catalog.enrichment.EnrichmentCandidates;
import ai.helper2.catalog.enrichment.EnrichmentCandidates.Candidate;
import ai.helper2.catalog.enrichment.EnrichmentResults;
import ai.helper2.catalog.enrichment.StoredEnrichments;
import ai.helper2.scout.CheckedCitation;
import ai.helper2.scout.EnrichmentBacklog;
import ai.helper2.scout.EnrichmentInput;
import ai.helper2.scout.EnrichmentMaterial;
import ai.helper2.scout.EnrichmentModel;
import ai.helper2.scout.EnrichmentOutcome;
import ai.helper2.scout.EnrichmentStore;
import ai.helper2.scout.GapSourceRecord;
import ai.helper2.scout.GapTarget;
import ai.helper2.scout.GapTargets;
import ai.helper2.scout.Material;
import ai.helper2.scout.PageTargets;
import ai.helper2.scout.QueuePlace;
import ai.helper2.scout.ValidationResult;
import ai.helper2.shared.ResourceType;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
class CatalogEnrichmentStore implements EnrichmentStore, EnrichmentBacklog {
  private static final Logger log = LoggerFactory.getLogger(CatalogEnrichmentStore.class);

  static final int MAX_FAILED_ATTEMPTS = 3;

  static final int MIN_DESCRIPTION = 40;

  private final EnrichmentCandidates candidates;
  private final EnrichmentResults results;
  private final StoredEnrichments stored;
  private final KnownTargets knownTargets;
  private final AtomicReference<@Nullable Instant> oldestPending = new AtomicReference<>();

  CatalogEnrichmentStore(
      EnrichmentCandidates candidates,
      EnrichmentResults results,
      StoredEnrichments stored,
      KnownTargets knownTargets,
      MeterRegistry meters,
      Clock clock) {
    this.candidates = candidates;
    this.results = results;
    this.stored = stored;
    this.knownTargets = knownTargets;
    Gauge.builder("helper2.scout.enrichment.oldest_pending.age", () -> ageOfOldest(clock))
        .baseUnit("seconds")
        .register(meters);
  }

  private double ageOfOldest(Clock clock) {
    Instant oldest = oldestPending.get();
    return oldest == null ? Double.NaN : Duration.between(oldest, clock.instant()).toSeconds();
  }

  @Override
  public List<UUID> eligible(List<UUID> resourceIds) {
    return candidates.withEnoughText(resourceIds, MIN_DESCRIPTION);
  }

  @Override
  public void requestRun(UUID resourceId) {
    stored.requestRun(resourceId);
  }

  @Override
  public List<UUID> pending(int limit) {
    List<UUID> batch = candidates.pending(limit, MIN_DESCRIPTION, MAX_FAILED_ATTEMPTS);
    readOldestPending();
    return batch;
  }

  private void readOldestPending() {
    try {
      oldestPending.set(candidates.oldestPending(MIN_DESCRIPTION));
    } catch (RuntimeException e) {
      log.warn("oldest pending enrichment not read: {}", e.getClass().getSimpleName());
    }
  }

  @Override
  public long pendingCount() {
    return candidates.pendingCount(MIN_DESCRIPTION, MAX_FAILED_ATTEMPTS);
  }

  @Override
  public List<EnrichmentCandidates.BacklogEntry> queue(
      EnrichmentCandidates.View view, EnrichmentCandidates.BacklogCursor after, int size) {
    return candidates.backlog(view, after, size, MIN_DESCRIPTION, MAX_FAILED_ATTEMPTS);
  }

  @Override
  public QueuePlace place(UUID resourceId) {
    EnrichmentCandidates.Standing s =
        candidates.standing(resourceId, MIN_DESCRIPTION, MAX_FAILED_ATTEMPTS);
    return s == null
        ? null
        : new QueuePlace(s.due(), s.outOfRetries(), s.skipped(), s.prioritised());
  }

  @Override
  public int prioritisedCount() {
    return candidates.prioritisedCount(MIN_DESCRIPTION, MAX_FAILED_ATTEMPTS);
  }

  @Override
  public EnrichmentMaterial load(UUID resourceId) {
    Candidate candidate = candidates.candidate(resourceId);
    if (candidate == null) {
      return null;
    }
    List<Material> materials =
        candidate.sources().stream()
            .map(s -> new Material(s.recordId(), URI.create(s.url()), s.retrievedAt(), s.text()))
            .toList();
    Map<String, String> facts = new HashMap<>();
    if (candidate.license() != null) {
      facts.put("license", candidate.license());
    }
    if (!"OTHER".equals(candidate.type())) {
      facts.put("type", candidate.type());
    }
    if (candidate.openSource() != null) {
      facts.put("openSource", candidate.openSource().toString());
    }
    facts.putAll(candidate.curated());
    String allText =
        String.join(
            "\n", candidate.sources().stream().map(EnrichmentCandidates.SourceText::text).toList());
    List<GapSourceRecord> records =
        candidate.sources().stream()
            .map(s ->
                new GapSourceRecord(
                    s.recordId(), s.sourceId(), s.externalId(), s.url(), s.repositoryUrl()))
            .toList();
    GapTarget gap = GapTargets.select(records);
    ResourceType type = typeOf(candidate.type());
    return new EnrichmentMaterial(
        new EnrichmentInput(
            candidate.title(),
            materials,
            facts,
            Map.of(),
            type,
            candidate.curated().keySet(),
            candidate.sourceStated()),
        sha(allText),
        gap,
        type,
        PageTargets.select(records));
  }

  private static ResourceType typeOf(String type) {
    try {
      return ResourceType.valueOf(type);
    } catch (IllegalArgumentException | NullPointerException e) {
      return ResourceType.OTHER;
    }
  }

  @Override
  public void recordArchived(UUID recordId, boolean archived, Instant checkedAt) {
    candidates.recordArchived(recordId, archived, checkedAt);
  }

  @Override
  public String lastFingerprint(UUID resourceId) {
    return candidates.lastJudgedFingerprint(resourceId);
  }

  @Override
  public Map<String, UUID> resourcesTitled(Collection<String> titles, UUID resourceId) {
    List<String> wanted =
        titles.stream()
            .map(t -> t.strip().toLowerCase())
            .filter(t -> t.length() >= ScoutPolicy.MIN_TARGET_NAME_CHARS)
            .distinct()
            .toList();
    if (wanted.isEmpty()) {
      return Map.of();
    }
    return candidates.titled(wanted, resourceId);
  }

  @Override
  public Map<String, KnownTarget> knownTargets(Collection<String> names) {
    return knownTargets.named(names);
  }

  @Override
  public void save(
      UUID resourceId,
      EnrichmentModel model,
      String inputFingerprint,
      Instant startedAt,
      ValidationResult result,
      EnrichmentOutcome outcome,
      String failure,
      String extraEvidence) {
    List<String> errors =
        result != null ? result.getErrors() : failure != null ? List.of(failure) : List.of();
    results.record(
        new EnrichmentResults.Run(
            resourceId,
            model.getProvider(),
            model.getModel(),
            model.getPromptVersion(),
            inputFingerprint,
            startedAt,
            outcome.name(),
            errors,
            extraEvidence,
            result != null ? !result.getForReview().isEmpty() : !errors.isEmpty()),
        result == null ? null : checked(result));
  }

  private static EnrichmentResults.Checked checked(ValidationResult result) {
    return new EnrichmentResults.Checked(
        result.getFields().stream()
            .map(f ->
                new EnrichmentResults.Field(
                    f.getField(),
                    f.getValue(),
                    f.getVerification(),
                    citations(f.getCitations()),
                    f.getOrigin()))
            .toList(),
        result.getRelationships().stream()
            .map(r ->
                new EnrichmentResults.Relationship(
                    r.getKind(),
                    r.getTarget(),
                    r.getTargetId(),
                    r.getVerification(),
                    citations(r.getCitations()),
                    r.getOrigin(),
                    r.getKnownKind(),
                    r.getStatedKind()))
            .toList(),
        result.getSummary().stream()
            .map(c ->
                new EnrichmentResults.Claim(
                    c.getText(), citations(c.getCitations()), c.getSelfDeclared()))
            .toList());
  }

  private static List<EnrichmentResults.Citation> citations(List<CheckedCitation> citations) {
    return citations.stream()
        .map(c ->
            new EnrichmentResults.Citation(
                c.getSourceRecordId(), c.getExcerpt(), c.getSourceUrl(), c.getRetrievedAt()))
        .toList();
  }

  private static String sha(String s) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)),
              0,
              16);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
