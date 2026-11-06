package ai.helper2.identity;

import org.jspecify.annotations.Nullable;

/**
 * Outbound port: what an administrator did (ADR 0023). Every admin use case records its action
 * here, in the same transaction as the change it makes, so the trail never claims a change that was
 * rolled back. Append-only.
 */
public interface AuditTrail {

  enum Action {
    RESOLVE_REVIEW_ITEM,
    START_SCOUT_RUN,
    ADD_HOME_EXAMPLE,
    CHANGE_HOME_EXAMPLE,
    REMOVE_HOME_EXAMPLE,
    DRAFT_HOME_EXAMPLES,
    RECHECK_ENRICHMENT,
    BACKFILL_CATEGORIES,
    // The enrichment queue actions (V14): listed here so the enum matches the check of V15.
    REQUEUE_ENRICHMENT,
    SKIP_ENRICHMENT,
    PRIORITISE_ENRICHMENT,
    CHANGE_SETTING,
    // Discovery's host lists (V17, ADR 0063).
    ADD_DISCOVERY_HOST,
    REMOVE_DISCOVERY_HOST
  }

  /**
   * What a change made: the value before and after, and why. Values are short text ("500",
   * "default"); the reason is the administrator's own words, at most 500 characters.
   */
  record Change(@Nullable String before, @Nullable String after, @Nullable String reason) {}

  /**
   * @param adminId the administrator's provider subject (GitHub: the numeric user id)
   * @param targetId what the action was about (a review item id, a source id, a home example id),
   *     or null for "everything"
   * @param outcome what happened, in a few upper-case words (ACCEPTED, STARTED, BUSY, ...)
   */
  void record(String adminId, Action action, @Nullable String targetId, String outcome);

  /**
   * The same, with the value before and after the change (a runtime setting, ADR 0060). A trail
   * that keeps no values records the action only.
   */
  default void record(
      String adminId, Action action, @Nullable String targetId, String outcome, Change change) {
    record(adminId, action, targetId, outcome);
  }
}
