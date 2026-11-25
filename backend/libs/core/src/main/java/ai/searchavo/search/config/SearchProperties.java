package ai.searchavo.search.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.convert.DurationUnit;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("searchavo.search")
public record SearchProperties(
    @NotNull Duration staleFor,
    @Positive int perSourceLimit,
    @DurationUnit(ChronoUnit.MILLIS) @NotNull Duration translateTimeout,
    @Valid @NotNull Translation translation,
    @Valid @NotNull RankingSignals ranking,
    @Valid @NotNull Web web) {
  /**
   * UC-001 BR-023: what the directory rating adds to or takes from a score, at most. 0 leaves the
   * rating out of the ranking; the bound is the weight of the popularity.
   */
  public record RankingSignals(@DecimalMin("0") @DecimalMax("0.008") double ratingWeight) {}

  public record Translation(@PositiveOrZero int dailyCalls, @NotNull Duration ttl) {}

  public record Web(
      @NotNull Duration freshTimeBound,
      @NotNull Duration freshGeneral,
      @Valid @NotNull Quota quota) {}

  public record Quota(@Valid @NotNull Limits guest, @PositiveOrZero Long globalDailyCredits) {}

  public record Limits(@PositiveOrZero int search, @PositiveOrZero int guide) {}
}
