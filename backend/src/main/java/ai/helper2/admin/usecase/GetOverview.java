package ai.helper2.admin.usecase;

import ai.helper2.catalog.ReviewQueue;
import ai.helper2.examples.PoolCounts;
import ai.helper2.examples.usecase.CountHomeExamples;
import ai.helper2.platform.llm.BudgetLedger;
import ai.helper2.platform.settings.RuntimeSettings;
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
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * What needs a person now (ADR 0045): open review items per kind, the enrichment backlog, today's
 * model budgets and web credits, the last Scout runs and how many failed in the last day, and how
 * many home examples are shown and hidden (ADR 0049), and how many runtime settings are not at
 * their default (ADR 0060). Counts only. Computed at most once per {@link #FRESH}: the backlog
 * count is a full pass, and one admin pressing Refresh must not repeat it.
 */
public class GetOverview {

  /** How long one computed overview is shown again. */
  public static final Duration FRESH = Duration.ofMinutes(1);

  static final int RECENT_RUNS = 10;

  /**
   * Runs read to count the failures of the last day; a day has far fewer runs (one per collector
   * and period).
   */
  static final int RUNS_FOR_FAILURES = 200;

  public record Overview(
      Instant generatedAt,
      Map<ReviewQueue.Kind, Long> openReviewItems,
      long enrichmentBacklog,
      List<BudgetLedger.Reading> budgets,
      @Nullable WebCredits webCredits,
      List<RunSummary> recentRuns,
      int failedRunsLast24h,
      PoolCounts homeExamples,
      int overridesActive) {}

  private final ReviewQueue reviewQueue;
  private final EnrichmentBacklog backlog;
  private final ScoutStore runs;
  private final BudgetLedger budgets;
  private final Supplier<@Nullable WebSearchQuota> webSearchQuota;
  private final CountHomeExamples homeExamples;
  private final RuntimeSettings runtimeSettings;
  private final Clock clock;
  private @Nullable Overview last;

  public GetOverview(
      ReviewQueue reviewQueue,
      EnrichmentBacklog backlog,
      ScoutStore runs,
      BudgetLedger budgets,
      Supplier<@Nullable WebSearchQuota> webSearchQuota,
      CountHomeExamples homeExamples,
      RuntimeSettings runtimeSettings,
      Clock clock) {
    this.reviewQueue = reviewQueue;
    this.backlog = backlog;
    this.runs = runs;
    this.budgets = budgets;
    this.webSearchQuota = webSearchQuota;
    this.homeExamples = homeExamples;
    this.runtimeSettings = runtimeSettings;
    this.clock = clock;
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
    WebSearchQuota quota = webSearchQuota.get();
    return new Overview(
        now,
        reviewQueue.openCountsByKind(),
        backlog.pendingCount(),
        budgets.today(),
        quota == null ? null : quota.creditsToday(),
        recent.stream().limit(RECENT_RUNS).toList(),
        failedSince(recent, now.minus(Duration.ofDays(1))),
        homeExamples.count(),
        runtimeSettings.overrideCount());
  }

  /** Runs that FAILED and started at or after [since]. */
  static int failedSince(List<RunSummary> runs, Instant since) {
    return (int)
        runs.stream()
            .filter(r -> r.getStatus() == RunStatus.FAILED && !r.getStartedAt().isBefore(since))
            .count();
  }
}
