package ai.helper2.catalog.internal;

final class BacklogRule {

  private BacklogRule() {}

  private static final String ACCEPTED =
      """
      EXISTS (SELECT 1 FROM candidate_decision d
              WHERE d.kind = 'RESOURCE_CANDIDATE' AND d.status = 'ACCEPTED'
                AND d.candidate_key = r.id)
      """;

  private static final String ENOUGH_TEXT =
      """
      EXISTS (SELECT 1 FROM source_record s
              WHERE s.resource_id = r.id AND length(coalesce(s.description, '')) >= :minText)
      """;

  private static final String ELIGIBLE =
      "r.data_origin IN ('INDEXED', 'OBSERVED', 'CURATED') AND ("
          + ENOUGH_TEXT
          + " OR "
          + ACCEPTED
          + ")";

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

  static final String NEW_DUE = "r.enrichment_status = 'NOT_RUN' AND " + ELIGIBLE;

  static final String REST_DUE =
      "r.enrichment_status IN ('LLM_UNAVAILABLE', 'REJECTED') AND "
          + ELIGIBLE
          + " AND (r.enrichment_status = 'LLM_UNAVAILABLE' OR ("
          + LAST_RUN_FAILED
          + " AND "
          + FAILED_RUNS
          + " < :maxFailed))";

  static final String DUE = "((" + NEW_DUE + ") OR (" + REST_DUE + "))";

  static final String OUT_OF_RETRIES =
      "r.enrichment_status = 'REJECTED' AND "
          + ELIGIBLE
          + " AND "
          + LAST_RUN_FAILED
          + " AND "
          + FAILED_RUNS
          + " >= :maxFailed";

  static final String HELD_AT_TOP =
      """
      c.prioritised_at IS NOT NULL
        AND NOT EXISTS (SELECT 1 FROM enrichment_run e
                        WHERE e.resource_id = c.resource_id AND e.started_at >= c.prioritised_at)
      """;

  static final String SKIPPED =
      "EXISTS (SELECT 1 FROM enrichment_queue_control c WHERE c.resource_id = r.id"
          + " AND c.skipped_at IS NOT NULL)";

  static final String PRIORITISED =
      "EXISTS (SELECT 1 FROM enrichment_queue_control c WHERE c.resource_id = r.id AND "
          + HELD_AT_TOP
          + ")";

  private static final String UNMOVED =
      "NOT EXISTS (SELECT 1 FROM enrichment_queue_control c WHERE c.resource_id = r.id"
          + " AND (c.skipped_at IS NOT NULL OR ("
          + HELD_AT_TOP
          + ")))";

  static final String PRIORITISED_FROM =
      "enrichment_queue_control c JOIN resource r ON r.id = c.resource_id WHERE "
          + HELD_AT_TOP
          + " AND "
          + DUE;

  static final String NEW_PART = NEW_DUE + " AND " + UNMOVED;

  static final String REST_PART = REST_DUE + " AND " + UNMOVED;
}
