package ai.helper2.identity;

import org.jspecify.annotations.Nullable;

/**
 * Outbound port: what an administrator did (ADR 0024). Every admin use case records its action
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
    // The enrichment queue actions. The check on admin_audit.action in V1 lists the same values.
    REQUEUE_ENRICHMENT,
    SKIP_ENRICHMENT,
    PRIORITISE_ENRICHMENT,
    CHANGE_SETTING,
    // Discovery's host lists (ADR 0022).
    ADD_DISCOVERY_HOST,
    REMOVE_DISCOVERY_HOST,
    // A relationship candidate's target, and a resource added for it (ADR 0026).
    CHANGE_RELATIONSHIP_TARGET,
    ADD_RESOURCE,
    // An admin's edit of a resource candidate (ADR 0026).
    EDIT_CANDIDATE,
    // A resource's own field set or the AI's value dropped (ADR 0026).
    SET_FIELD,
    DROP_FIELD
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
   * The same, with the value before and after the change (a runtime setting, ADR 0007). A trail
   * that keeps no values records the action only.
   */
  default void record(
      String adminId, Action action, @Nullable String targetId, String outcome, Change change) {
    record(adminId, action, targetId, outcome);
  }
}
