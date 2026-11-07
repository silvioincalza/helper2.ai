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
   * with fewer than [maxFailed] FAILED runs since an admin last requeued it. Only indexed, observed
   * and curated resources with at least one source description of [minText] characters or accepted
   * by a person from the review queue, and none an admin skipped. The ones an admin moved to the
   * top first (ADR 0026 (part was 0045) §7), then products a person accepted from the review queue
   * (ADR 0023), then never-run resources, then the rest, each part newest discoveries first; at
   * most [limit].
   */
  List<UUID> pending(int limit, int minText, int maxFailed);

  /**
   * How many resources [pending] would return without a limit; gives up after a few seconds (back
   * office overview).
   */
  long pendingCount(int minText, int maxFailed);

  /**
   * One page of [view] after [after] (null: from the start), at most [size] entries, with why each
   * waits and its runs so far (back office queue). The WAITING view is the backlog in the order
   * [pending] takes it.
   */
  List<BacklogEntry> backlog(
      View view, @Nullable BacklogCursor after, int size, int minText, int maxFailed);

  /**
   * Where one resource stands against the backlog rule and the admin's decisions; null for an
   * unknown resource.
   */
  @Nullable Standing standing(UUID id, int minText, int maxFailed);

  /** How many resources an admin moved to the top still wait for their next run. */
  int prioritisedCount(int minText, int maxFailed);

  /** Which resources a queue page lists. */
  enum View {
    /** The backlog, in the Scout's order. */
    WAITING,
    /** Resources an admin skipped, until requeued; the latest skip first. */
    SKIPPED,
    /** Resources whose FAILED runs reached the cap, and not skipped. */
    OUT_OF_RETRIES
  }

  /** Where a resource stands in the queue, as the page shows it. */
  enum State {
    /** Moved to the top by an admin: taken before every other part. */
    PRIORITISED,
    /** In the backlog, in its own part. */
    WAITING,
    /** Skipped by an admin: not taken until requeued. */
    SKIPPED,
    /** Its FAILED runs reached the cap: not taken until requeued. */
    OUT_OF_RETRIES
  }

  /** Why a resource waits for enrichment. */
  enum Waiting {
    /** Never enriched: taken first. */
    NEW,
    /** The last run found no model to call. */
    MODEL_UNAVAILABLE,
    /** The last run FAILED (timeout, provider error) and retries are left. */
    RETRY
  }

  /**
   * One resource in a queue page, with its runs so far. [waiting] is null for a resource that does
   * not wait (skipped, out of retries). [failedRuns] counts the FAILED runs since an admin last
   * requeued it: the number the cap is checked against. [skippedAt] and [skipReason] are set for a
   * skipped resource only.
   */
  record BacklogEntry(
      UUID id,
      String title,
      State state,
      @Nullable Waiting waiting,
      Instant firstDiscoveredAt,
      int failedRuns,
      @Nullable String lastOutcome,
      @Nullable Instant lastRunAt,
      @Nullable Instant skippedAt,
      @Nullable String skipReason) {

    /** Where the next page starts. */
    public BacklogCursor cursor() {
      if (state == State.SKIPPED && skippedAt != null) {
        return new BacklogCursor(Part.REST, skippedAt, id);
      }
      Part part =
          state == State.PRIORITISED
              ? Part.PRIORITISED
              : waiting == Waiting.NEW ? Part.NEW : Part.REST;
      return new BacklogCursor(part, firstDiscoveredAt, id);
    }
  }

  /** The parts of the backlog, in the order a page lists them. */
  enum Part {
    /** Moved to the top by an admin. */
    PRIORITISED,
    /** Never run. */
    NEW,
    /** Model unavailable, or retries left; the only part of the other views. */
    REST
  }

  /**
   * A place in a queue view: the part, then newest first by [at] and id. [at] is the first
   * discovery, or the skip in the SKIPPED view (latest skip first, so a mistake is easy to undo).
   */
  record BacklogCursor(Part part, Instant at, UUID id) {}

  /**
   * One resource against the backlog rule. [due]: the rule takes it before any admin decision
   * (never run, model unavailable, or FAILED with retries left). [outOfRetries]: its last run
   * FAILED and its FAILED runs reached the cap. [skipped], [prioritised]: an admin's decision that
   * holds.
   */
  record Standing(boolean due, boolean outOfRetries, boolean skipped, boolean prioritised) {}

  /**
   * The subset of [ids] with a source description of at least [minText] characters and not skipped
   * by an admin (ADR 0026 (part was 0045) §7), in the given order. A collector's changed resources
   * go through it, so a skip holds there too.
   */
  List<UUID> withEnoughText(List<UUID> ids, int minText);

  /** The resource and its source records in source id order; null when it has no source record. */
  @Nullable Candidate candidate(UUID id);

  /**
   * Input fingerprint of the latest run that judged the model's answer (not LLM_UNAVAILABLE, not
   * FAILED) that started after the last re-run request ({@link StoredEnrichments#requestRun}, ADR
   * 0023); null when none.
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
      List<SourceText> sources,
      /** The fields an admin set (ADR 0026), with their values: facts no run changes. */
      Map<String, String> curated) {

    public Candidate(
        String title,
        String type,
        @Nullable Boolean openSource,
        @Nullable String license,
        List<SourceText> sources) {
      this(title, type, openSource, license, sources, Map.of());
    }
  }

  /**
   * One source record as material: [text] is its title, a line break, and its description (empty
   * when none). [sourceId], [externalId] and [repositoryUrl] say where the record came from and
   * which repository it names, so the Scout can pick the README or model card for its gap round
   * (ADR 0023).
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
