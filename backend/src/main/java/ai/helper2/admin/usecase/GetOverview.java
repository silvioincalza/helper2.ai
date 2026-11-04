package ai.helper2.admin.usecase;

import ai.helper2.catalog.ReviewQueue;
import ai.helper2.platform.llm.BudgetLedger;
import ai.helper2.scout.EnrichmentBacklog;
import ai.helper2.scout.RunStatus;
import ai.helper2.scout.RunSummary;
import ai.helper2.scout.ScoutStore;
import ai.helper2.search.WebCredits;
import ai.helper2.search.WebSearchQuota;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * What needs a person now (ADR 0045): open review items per kind, the enrichment backlog, today's
 * model budgets and web credits, the last Scout runs and how many failed in the last day. Counts
 * only. Computed at most once per {@link #FRESH}: the backlog count is a full pass, and one admin
 * pressing Refresh must not repeat it.
 */
@Service
public class GetOverview {

  /** How long one computed overview is shown again. */
  public static final Duration FRESH = Duration.ofMinutes(1);

  static final int RECENT_RUNS = 10;

  /**
   * Runs read to count the failures of the last day; a day has far fewer runs (one per collector
   * and period).
   */
  static final int RUNS_FOR_FAILURES = 200;

  private final ReviewQueue reviewQueue;
  private final EnrichmentBacklog backlog;
  private final ScoutStore runs;
  private final BudgetLedger budgets;
  private final ObjectProvider<WebSearchQuota> webSearchQuota;
  private final Clock clock;
  private @Nullable Overview last;

  public GetOverview(
      ReviewQueue reviewQueue,
      EnrichmentBacklog backlog,
      ScoutStore runs,
      BudgetLedger budgets,
      ObjectProvider<WebSearchQuota> webSearchQuota,
      Clock clock) {
    this.reviewQueue = reviewQueue;
    this.backlog = backlog;
    this.runs = runs;
    this.budgets = budgets;
    this.webSearchQuota = webSearchQuota;
    this.clock = clock;
  }

  /** Runs that FAILED and started at or after [since]. */
  static int failedSince(List<RunSummary> runs, Instant since) {
    return (int)
        runs.stream()
            .filter(r -> r.getStatus() == RunStatus.FAILED && !r.getStartedAt().isBefore(since))
            .count();
  }

  /**
   * The overview, computed again when the last one is older than {@link #FRESH}. One computation at
   * a time.
   */
  public synchronized Overview get() {
    Instant now = clock.instant();
    if (last == null || !now.isBefore(last.generatedAt().plus(FRESH))) {
      last = compute(now);
    }
    return last;
  }

  private Overview compute(Instant now) {
    List<RunSummary> recent = runs.recent(RUNS_FOR_FAILURES);
    WebSearchQuota quota = webSearchQuota.getIfAvailable();
    return new Overview(
        now,
        reviewQueue.openCountsByKind(),
        backlog.pendingCount(),
        budgets.today(),
        quota == null ? null : quota.creditsToday(),
        recent.stream().limit(RECENT_RUNS).toList(),
        failedSince(recent, now.minus(Duration.ofDays(1))));
  }

  public record Overview(
      Instant generatedAt,
      Map<ReviewQueue.Kind, Long> openReviewItems,
      long enrichmentBacklog,
      List<BudgetLedger.Reading> budgets,
      @Nullable WebCredits webCredits,
      List<RunSummary> recentRuns,
      int failedRunsLast24h) {}
}
