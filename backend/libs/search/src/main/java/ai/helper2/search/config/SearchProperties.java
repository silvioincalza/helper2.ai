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

/**
 * The search module's settings, from {@code helper2.search}. The defaults and the {@code H2_}
 * variables are in application.yaml (ADR 0007 point 8).
 *
 * @param staleFor how long a source's answer is cached and served when the source fails
 * @param perSourceLimit most results asked of each source per search
 * @param translateTimeout how long search waits for the English translation; a plain number is
 *     milliseconds
 * @param translation the query translation (ADR 0011)
 * @param web the metered web search (Tavily, ADR 0037)
 */
@Validated
@ConfigurationProperties("helper2.search")
public record SearchProperties(
    @NotNull Duration staleFor,
    @Positive int perSourceLimit,
    @DurationUnit(ChronoUnit.MILLIS) @NotNull Duration translateTimeout,
    @Valid @NotNull Translation translation,
    @Valid @NotNull Web web) {

  /**
   * Non-English queries in English words, by the fast model.
   *
   * @param dailyCalls model calls per UTC day, apart from the guide's cap
   * @param ttl how long the replicas share a translation
   */
  public record Translation(
      boolean enabled, @PositiveOrZero int dailyCalls, @NotNull Duration ttl) {}

  /**
   * Stored result sets answer without a live call while fresh.
   *
   * @param freshTimeBound for news, courses, hackathons, conferences and events
   * @param freshGeneral for anything else
   */
  public record Web(
      @NotNull Duration freshTimeBound,
      @NotNull Duration freshGeneral,
      @Valid @NotNull Quota quota) {}

  /**
   * Daily web searches per visitor and feature.
   *
   * @param registeredTierEnabled signed-in users get the registered limits only with this on
   * @param globalDailyCredits live calls a day for all of Helper2; null (empty) = no cap
   */
  public record Quota(
      @Valid @NotNull Limits guest,
      @Valid @NotNull Limits registered,
      boolean registeredTierEnabled,
      @PositiveOrZero Long globalDailyCredits) {}

  /** Live searches per UTC day, per feature. */
  public record Limits(@PositiveOrZero int search, @PositiveOrZero int guide) {}
}
