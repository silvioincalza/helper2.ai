package ai.helper2.scout.config;

import ai.helper2.platform.llm.ModelTier;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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
    @NotNull ModelTier enrichmentModel,
    @Valid @NotNull Evidence evidence,
    @Valid @NotNull KnownTargets knownTargets,
    @Valid @NotNull RepositorySweep repositorySweep) {
  public record RepositorySweep(
      boolean enabled,
      @NotNull Duration every,
      @NotNull Duration initialDelay,
      @Min(1) @Max(100) int batch,
      @Min(1) int goneListVisits) {}

  public record KnownTargets(@Positive int ownerMinResources, @PositiveOrZero int perDay) {}

  public record Evidence(@PositiveOrZero int dailyPageReadCredits) {}

  @AssertTrue(message = "must be a Spring cron expression")
  public boolean isCronReadable() {
    return cron == null || CronExpression.isValidExpression(cron);
  }
}
