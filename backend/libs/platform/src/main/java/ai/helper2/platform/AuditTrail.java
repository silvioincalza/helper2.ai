package ai.helper2.platform;

import org.jspecify.annotations.Nullable;

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
    REQUEUE_ENRICHMENT,
    SKIP_ENRICHMENT,
    PRIORITISE_ENRICHMENT,
    CHANGE_SETTING,
    ADD_DISCOVERY_HOST,
    REMOVE_DISCOVERY_HOST,
    CHANGE_RELATIONSHIP_TARGET,
    ADD_RESOURCE,
    EDIT_CANDIDATE,
    SET_FIELD,
    DROP_FIELD,
    ADD_KNOWN_TARGET,
    EDIT_KNOWN_TARGET,
    REMOVE_KNOWN_TARGET
  }

  record Change(@Nullable String before, @Nullable String after, @Nullable String reason) {}

  void record(String adminId, Action action, @Nullable String targetId, String outcome);

  default void record(
      String adminId, Action action, @Nullable String targetId, String outcome, Change change) {
    record(adminId, action, targetId, outcome);
  }
}
