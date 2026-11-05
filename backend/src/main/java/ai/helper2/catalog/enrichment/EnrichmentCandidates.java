package ai.helper2.catalog.enrichment;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Outbound port of the catalog for enrichment: which resources still need a run, and what the
 * sources stored about one of them. The caller sets the bars (minimum text, failed attempts); the
 * catalog owns the tables and the SQL.
 */
public interface EnrichmentCandidates {

  /**
   * Resources never enriched, enriched while no LLM was available, or whose last attempt FAILED
   * with fewer than [maxFailed] FAILED runs so far. Only indexed, observed and curated resources
   * with at least one source description of [minText] characters. Never-run resources first, then
   * newest discoveries first; at most [limit].
   */
  List<UUID> pending(int limit, int minText, int maxFailed);

  /**
   * How many resources [pending] would return without a limit; gives up after a few seconds (back
   * office overview).
   */
  long pendingCount(int minText, int maxFailed);

  /**
   * One page of the backlog in the order [pending] takes it, after [after] (null: from the start),
   * at most [size] entries, with why each waits and its runs so far (back office queue).
   */
  List<BacklogEntry> backlog(@Nullable BacklogCursor after, int size, int minText, int maxFailed);

  /** Why a resource waits for enrichment. */
  enum Waiting {
    /** Never enriched: taken first. */
    NEW,
    /** The last run found no model to call. */
    MODEL_UNAVAILABLE,
    /** The last run FAILED (timeout, provider error) and retries are left. */
    RETRY
  }

  /** One resource in the backlog, with its runs so far. */
  record BacklogEntry(
      UUID id,
      String title,
      Waiting waiting,
      Instant firstDiscoveredAt,
      int failedRuns,
      @Nullable String lastOutcome,
      @Nullable Instant lastRunAt) {

    /** Where the next page starts. */
    public BacklogCursor cursor() {
      return new BacklogCursor(waiting == Waiting.NEW, firstDiscoveredAt, id);
    }
  }

  /**
   * A place in the backlog: the never-run part or the rest, then newest first by discovery and id.
   */
  record BacklogCursor(boolean neverRun, Instant firstDiscoveredAt, UUID id) {}

  /**
   * The subset of [ids] with a source description of at least [minText] characters, in the given
   * order.
   */
  List<UUID> withEnoughText(List<UUID> ids, int minText);

  /** The resource and its source records in source id order; null when it has no source record. */
  @Nullable Candidate candidate(UUID id);

  /**
   * Input fingerprint of the latest run that judged the model's answer (not LLM_UNAVAILABLE, not
   * FAILED); null when none.
   */
  @Nullable String lastJudgedFingerprint(UUID id);

  /**
   * Resources other than [excluding] whose lower-case title is one of [lowerTitles], by lower-case
   * title. For duplicate titles the earliest discovered resource wins.
   */
  Map<String, UUID> titled(Collection<String> lowerTitles, UUID excluding);

  /**
   * What the sources state about one resource. [license] comes from the first source record (by
   * source id) that has one; [type] is the catalog's type, OTHER included.
   */
  record Candidate(
      String title,
      String type,
      @Nullable Boolean openSource,
      @Nullable String license,
      List<SourceText> sources) {}

  /**
   * One source record as material: [text] is its title, a line break, and its description (empty
   * when none). [sourceId], [externalId] and [repositoryUrl] say where the record came from and
   * which repository it names, so the Scout can pick the README or model card for its gap round
   * (ADR 0043).
   */
  record SourceText(
      UUID recordId,
      String url,
      Instant retrievedAt,
      String text,
      String sourceId,
      String externalId,
      @Nullable String repositoryUrl) {}
}
