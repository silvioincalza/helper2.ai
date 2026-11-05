package ai.helper2.scout.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.validation.annotation.Validated;

/**
 * The Scout's schedule and budgets, from {@code helper2.scout}. Each collector's own settings sit
 * under {@code helper2.scout.<collector>} and bind to the collector records of the sources module.
 * The defaults and the {@code H2_} variables are in application.yaml (ADR 0007 point 8).
 *
 * @param enabled the schedule; an admin can start a run either way
 * @param initialDelay the first check after startup
 * @param tick how often each replica checks whether a run is due
 * @param cron when the daily run is due (Spring cron, UTC; ADR 0018)
 * @param maxItemsPerRun the most items one run collects
 * @param maxEnrichmentsPerRun enrichment calls per run, collectors and backlog together
 * @param dailyEnrichmentCalls enrichment model calls per UTC day, apart from the guide's cap
 * @param gapDailyCalls gap rounds per UTC day (ADR 0043), inside dailyEnrichmentCalls; 0 = off
 * @param enrichMaxTokens output cap of one enrichment call
 */
@Validated
@ConfigurationProperties("helper2.scout")
public record ScoutProperties(
    boolean enabled,
    @NotNull Duration initialDelay,
    @NotNull Duration tick,
    @NotNull String cron,
    @Positive int maxItemsPerRun,
    @PositiveOrZero int maxEnrichmentsPerRun,
    @PositiveOrZero int dailyEnrichmentCalls,
    @PositiveOrZero int gapDailyCalls,
    @Positive int enrichMaxTokens) {

  @AssertTrue(message = "must be a Spring cron expression")
  public boolean isCronReadable() {
    return cron == null || CronExpression.isValidExpression(cron);
  }
}
