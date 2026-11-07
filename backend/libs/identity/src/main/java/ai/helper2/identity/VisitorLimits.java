package ai.helper2.identity;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Outbound port: what a visitor can still do today (ADR 0028, GET /me). Implemented by the guide,
 * which owns the daily questions and reaches the web-search quota through search. Reads the
 * counters that enforce the limits and never counts. The client key and the user id only select the
 * counters; nothing returned identifies the visitor.
 */
public interface VisitorLimits {

  /** A daily limit and what is left of it today. */
  record Allowance(int limit, int remaining) {}

  /**
   * @param guideQuestions fresh guide questions for this client; null without a guide model
   * @param searchWebSearches live web searches of search; null without an enabled web source or
   *     when the count cannot be read
   * @param guideWebSearches live web searches of the guide; null as for search
   */
  record Today(
      @Nullable Allowance guideQuestions,
      @Nullable Allowance searchWebSearches,
      @Nullable Allowance guideWebSearches) {}

  /**
   * @param client the client key ({@code ClientKey.of} of the request address)
   * @param userId the signed-in user, or null
   */
  Today of(String client, @Nullable UUID userId);
}
