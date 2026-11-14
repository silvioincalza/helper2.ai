package ai.helper2.search.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.convert.DurationUnit;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("helper2.search")
public record SearchProperties(
    @NotNull Duration staleFor,
    @Positive int perSourceLimit,
    @DurationUnit(ChronoUnit.MILLIS) @NotNull Duration translateTimeout,
    @Valid @NotNull Translation translation,
    @Valid @NotNull Web web) {
  public record Translation(@PositiveOrZero int dailyCalls, @NotNull Duration ttl) {}

  public record Web(
      @NotNull Duration freshTimeBound,
      @NotNull Duration freshGeneral,
      @Valid @NotNull Quota quota) {}

  public record Quota(@Valid @NotNull Limits guest, @PositiveOrZero Long globalDailyCredits) {}

  public record Limits(@PositiveOrZero int search, @PositiveOrZero int guide) {}
}
