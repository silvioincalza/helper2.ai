package ai.helper2.scout.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.validation.annotation.Validated;

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
    @Positive int enrichMaxTokens,
    @Valid @NotNull Evidence evidence,
    @Valid @NotNull KnownTargets knownTargets) {
  public record KnownTargets(@Positive int ownerMinResources, @PositiveOrZero int perDay) {}

  public record Evidence(
      @PositiveOrZero int dailyPageReadCredits,
      @NotNull Duration pageReadTtl,
      @NotNull Duration pageTextTtl) {
    @AssertTrue(message = "must be more than 0 and at most P1D")
    public boolean isPageTextTtlAtMostADay() {
      return pageTextTtl == null
          || (!pageTextTtl.isNegative()
              && !pageTextTtl.isZero()
              && pageTextTtl.compareTo(Duration.ofDays(1)) <= 0);
    }
  }

  @AssertTrue(message = "must be a Spring cron expression")
  public boolean isCronReadable() {
    return cron == null || CronExpression.isValidExpression(cron);
  }
}
