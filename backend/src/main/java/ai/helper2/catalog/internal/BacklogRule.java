package ai.helper2.catalog.internal;

/**
 * The enrichment backlog rule as SQL fragments over a resource {@code r}: one rule for the Scout's
 * next batch, the back office queue and its count (ADR 0045 §6, §7). Parameters: {@code :minText},
 * {@code :maxFailed}.
 *
 * <p>The admin's decisions are rows of {@code enrichment_queue_control}. The Scout owns that table
 * and is its only writer; these fragments only read it, so the order the Scout takes and the order
 * the page shows cannot drift apart.
 */
final class BacklogRule {

  private BacklogRule() {}

  /** Resources the Scout may enrich: indexed, observed or curated, with enough source text. */
  private static final String ELIGIBLE =
      """
      r.data_origin IN ('INDEXED', 'OBSERVED', 'CURATED')
        AND EXISTS (SELECT 1 FROM source_record s
                    WHERE s.resource_id = r.id AND length(coalesce(s.description, '')) >= :minText)
      """;

  /** The FAILED runs since an admin last requeued the resource; every one before that. */
  static final String FAILED_RUNS =
      """
      (SELECT count(*) FROM enrichment_run e
       WHERE e.resource_id = r.id AND e.outcome = 'FAILED'
         AND e.started_at > coalesce((SELECT c.failures_reset_at FROM enrichment_queue_control c
                                      WHERE c.resource_id = r.id), '-infinity'))
      """;

  private static final String LAST_RUN_FAILED =
      """
      (SELECT e.outcome FROM enrichment_run e WHERE e.resource_id = r.id
       ORDER BY e.started_at DESC LIMIT 1) = 'FAILED'
      """;

  /** Never enriched, before any admin decision. */
  static final String NEW_DUE = "r.enrichment_status = 'NOT_RUN' AND " + ELIGIBLE;

  /**
   * Enriched while no model was available, or whose last run FAILED (stored as REJECTED) with fewer
   * than [maxFailed] FAILED runs; before any admin decision.
   */
  static final String REST_DUE =
      "r.enrichment_status IN ('LLM_UNAVAILABLE', 'REJECTED') AND "
          + ELIGIBLE
          + " AND (r.enrichment_status = 'LLM_UNAVAILABLE' OR ("
          + LAST_RUN_FAILED
          + " AND "
          + FAILED_RUNS
          + " < :maxFailed))";

  /** The rule takes it, before any admin decision. */
  static final String DUE = "((" + NEW_DUE + ") OR (" + REST_DUE + "))";

  /**
   * The last run FAILED and the FAILED runs reached the cap: out of the backlog until an admin
   * requeues it. Still in the backlog index (status REJECTED).
   */
  static final String OUT_OF_RETRIES =
      "r.enrichment_status = 'REJECTED' AND "
          + ELIGIBLE
          + " AND "
          + LAST_RUN_FAILED
          + " AND "
          + FAILED_RUNS
          + " >= :maxFailed";

  /** A control row {@code c} moved to the top whose resource has not run since. */
  static final String HELD_AT_TOP =
      """
      c.prioritised_at IS NOT NULL
        AND NOT EXISTS (SELECT 1 FROM enrichment_run e
                        WHERE e.resource_id = c.resource_id AND e.started_at >= c.prioritised_at)
      """;

  /** An admin skipped it. */
  static final String SKIPPED =
      "EXISTS (SELECT 1 FROM enrichment_queue_control c WHERE c.resource_id = r.id"
          + " AND c.skipped_at IS NOT NULL)";

  /** An admin moved it to the top, and it has not run since. */
  static final String PRIORITISED =
      "EXISTS (SELECT 1 FROM enrichment_queue_control c WHERE c.resource_id = r.id AND "
          + HELD_AT_TOP
          + ")";

  /** Neither skipped nor at the top: the resource stays in its own part. */
  private static final String UNMOVED =
      "NOT EXISTS (SELECT 1 FROM enrichment_queue_control c WHERE c.resource_id = r.id"
          + " AND (c.skipped_at IS NOT NULL OR ("
          + HELD_AT_TOP
          + ")))";

  /**
   * The prioritised part: read from the control rows (at most 50 hold), joined to their resource.
   */
  static final String PRIORITISED_FROM =
      "enrichment_queue_control c JOIN resource r ON r.id = c.resource_id WHERE "
          + HELD_AT_TOP
          + " AND "
          + DUE;

  /** The never-run part, in the backlog index. */
  static final String NEW_PART = NEW_DUE + " AND " + UNMOVED;

  /** The rest: model unavailable or retries left, in the backlog index. */
  static final String REST_PART = REST_DUE + " AND " + UNMOVED;
}
