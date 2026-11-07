package ai.helper2.scout.internal;

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
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The Scout's view of the catalog for enrichment. Reads the backlog and a resource's source text
 * through {@link EnrichmentCandidates} and hands each run's checked answer to {@link
 * EnrichmentResults}; the catalog owns the tables and the SQL. The bars (minimum text, failed
 * attempts) are the Scout's rules, so they are set here.
 */
@Component
class CatalogEnrichmentStore implements EnrichmentStore, EnrichmentBacklog {

  /** A FAILED run is usually transient (timeout, provider error); give up after this many. */
  static final int MAX_FAILED_ATTEMPTS = 3;

  /** Minimum source description length for a resource to be worth an LLM call. */
  static final int MIN_DESCRIPTION = 40;

  private final EnrichmentCandidates candidates;
  private final EnrichmentResults results;
  private final StoredEnrichments stored;

  CatalogEnrichmentStore(
      EnrichmentCandidates candidates, EnrichmentResults results, StoredEnrichments stored) {
    this.candidates = candidates;
    this.results = results;
    this.stored = stored;
  }

  /** Same text bar as {@link #pending}: a bare title gives the model nothing worth citing. */
  @Override
  public List<UUID> eligible(List<UUID> resourceIds) {
    return candidates.withEnoughText(resourceIds, MIN_DESCRIPTION);
  }

  /** Back to NOT_RUN with the request time, so {@link #pending} takes it on a later run. */
  @Override
  public void requestRun(UUID resourceId) {
    stored.requestRun(resourceId);
  }

  /**
   * Never enriched, enriched while no LLM was available, or whose last attempt FAILED (stored as
   * REJECTED) with retries left. Only resources with a source description, or accepted by a person:
   * a bare title gives the model nothing worth citing, so it would only produce REJECTED runs,
   * unless the evidence ladder can read the product's own page (ADR 0056, ADR 0058). Newest
   * discoveries first.
   */
  @Override
  public List<UUID> pending(int limit) {
    return candidates.pending(limit, MIN_DESCRIPTION, MAX_FAILED_ATTEMPTS);
  }

  /**
   * How many resources {@link #pending} would return without a limit; for the back office overview.
   */
  @Override
  public long pendingCount() {
    return candidates.pendingCount(MIN_DESCRIPTION, MAX_FAILED_ATTEMPTS);
  }

  /**
   * The backlog page by page, with the same bars as {@link #pending}; for the back office queue.
   */
  @Override
  public List<EnrichmentCandidates.BacklogEntry> queue(
      EnrichmentCandidates.View view, EnrichmentCandidates.BacklogCursor after, int size) {
    return candidates.backlog(view, after, size, MIN_DESCRIPTION, MAX_FAILED_ATTEMPTS);
  }

  /** Where one resource stands, with the same bars as {@link #pending}. */
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

  /**
   * The source records as material, and what the sources themselves state (licence, type, open
   * source), which the model may not overwrite. A type of OTHER says nothing, so it is no fact.
   */
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
    // What an admin set wins over what any record says (ADR 0066).
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
            candidate.title(), materials, facts, Map.of(), type, candidate.curated().keySet()),
        sha(allText),
        gap,
        type,
        PageTargets.select(records));
  }

  /** The catalog's type; one this code does not know counts as OTHER (no target fields). */
  private static ResourceType typeOf(String type) {
    try {
      return ResourceType.valueOf(type);
    } catch (IllegalArgumentException | NullPointerException e) {
      return ResourceType.OTHER;
    }
  }

  /**
   * Runs without a judged answer (LLM_UNAVAILABLE, FAILED) say nothing about the input, so they are
   * passed over.
   */
  @Override
  public String lastFingerprint(UUID resourceId) {
    return candidates.lastJudgedFingerprint(resourceId);
  }

  /**
   * Exact, case-insensitive title match on the few targets the model named, never a scan of every
   * title against the material. Titles under 4 characters ("AI", "Go") are too ambiguous to link.
   * For duplicate titles the earliest discovered resource wins.
   */
  @Override
  public Map<String, UUID> resourcesTitled(Collection<String> titles, UUID resourceId) {
    List<String> wanted =
        titles.stream()
            .map(t -> t.strip().toLowerCase())
            .filter(t -> t.length() >= 4)
            .distinct()
            .toList();
    if (wanted.isEmpty()) {
      return Map.of();
    }
    return candidates.titled(wanted, resourceId);
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
            extraEvidence),
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
                    r.getOrigin()))
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
