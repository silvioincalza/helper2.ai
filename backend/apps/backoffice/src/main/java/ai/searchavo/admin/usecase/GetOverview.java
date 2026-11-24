package ai.searchavo.admin.usecase;

import ai.searchavo.catalog.ReviewQueue;
import ai.searchavo.catalog.enrichment.ValidationIssues;
import ai.searchavo.examples.PoolCounts;
import ai.searchavo.examples.usecase.CountHomeExamples;
import ai.searchavo.platform.llm.BudgetLedger;
import ai.searchavo.platform.settings.RuntimeSettings;
import ai.searchavo.scout.EnrichmentBacklog;
import ai.searchavo.scout.RunStatus;
import ai.searchavo.scout.RunSummary;
import ai.searchavo.scout.ScoutStore;
import ai.searchavo.search.WebCredits;
import ai.searchavo.search.WebSearchQuota;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

public class GetOverview {
  public static final Duration FRESH = Duration.ofMinutes(1);

  static final int RECENT_RUNS = 10;

  static final int RUNS_FOR_FAILURES = 200;

  public static final Duration ISSUE_WINDOW = Duration.ofDays(7);

  static final int TOP_ISSUES = 10;

  public record Overview(
      Instant generatedAt,
      Map<ReviewQueue.Kind, Long> openReviewItems,
      long enrichmentBacklog,
      List<BudgetLedger.Reading> budgets,
      @Nullable WebCredits webCredits,
      List<RunSummary> recentRuns,
      int failedRunsLast24h,
      PoolCounts homeExamples,
      int overridesActive,
      ValidationIssues.Summary validationIssues) {}

  private final ReviewQueue reviewQueue;
  private final EnrichmentBacklog backlog;
  private final ScoutStore runs;
  private final BudgetLedger budgets;
  private final Supplier<@Nullable WebSearchQuota> webSearchQuota;
  private final CountHomeExamples homeExamples;
  private final RuntimeSettings runtimeSettings;
  private final ValidationIssues validationIssues;
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
      ValidationIssues validationIssues,
      Clock clock) {
    this.reviewQueue = reviewQueue;
    this.backlog = backlog;
    this.runs = runs;
    this.budgets = budgets;
    this.webSearchQuota = webSearchQuota;
    this.homeExamples = homeExamples;
    this.runtimeSettings = runtimeSettings;
    this.validationIssues = validationIssues;
    this.clock = clock;
  }

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
        runtimeSettings.overrideCount(),
        validationIssues.since(now.minus(ISSUE_WINDOW), TOP_ISSUES));
  }

  static int failedSince(List<RunSummary> runs, Instant since) {
    return (int)
        runs.stream()
            .filter(r -> r.getStatus() == RunStatus.FAILED && !r.getStartedAt().isBefore(since))
            .count();
  }
}
