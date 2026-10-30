package ai.helper2.identity;

import org.jspecify.annotations.Nullable;

/**
 * Outbound port: what an administrator did (ADR 0023). Every admin use case records its action here, in the same
 * transaction as the change it makes, so the trail never claims a change that was rolled back. Append-only.
 */
public interface AuditTrail {

    enum Action { RESOLVE_REVIEW_ITEM, START_SCOUT_RUN }

    /**
     * @param adminId  the administrator's provider subject (GitHub: the numeric user id)
     * @param targetId what the action was about (a review item id, a source id), or null for "everything"
     * @param outcome  what happened, in a few upper-case words (ACCEPTED, STARTED, BUSY, ...)
     */
    void record(String adminId, Action action, @Nullable String targetId, String outcome);
}
