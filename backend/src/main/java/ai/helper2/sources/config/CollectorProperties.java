package ai.helper2.sources.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

/**
 * The settings of each Scout collector, bound from {@code helper2.scout.<collector>}. The defaults
 * and the environment variables are in application.yaml only (ADR 0007 point 8). A missing or
 * invalid value stops startup with the name of the property. Lists are trimmed and blank entries
 * dropped, so folded YAML lists can span lines.
 */
final class CollectorProperties {

  private CollectorProperties() {}

  /**
   * Every collector has a run interval (RunInterval): how long after a complete pass the next one
   * is due.
   */
  interface Scheduled {
    Duration interval();

    @AssertTrue(message = "must be zero or positive")
    default boolean isIntervalZeroOrPositive() {
      return interval() == null || !interval().isNegative();
    }
  }

  @Validated
  @ConfigurationProperties("helper2.scout.hf-trending")
  record HfTrending(@NotNull Duration interval, @PositiveOrZero int minLikes)
      implements Scheduled {}

  @Validated
  @ConfigurationProperties("helper2.scout.live-web")
  record LiveWebScout(@NotNull Duration interval, @NotNull List<String> queries)
      implements Scheduled {

    LiveWebScout {
      queries = clean(queries);
    }
  }

  @Validated
  @ConfigurationProperties("helper2.scout.courses")
  record Courses(@NotNull Duration interval, @NotNull String query, @NotNull List<String> platforms)
      implements Scheduled {

    Courses {
      platforms = clean(platforms);
    }
  }

  /** LinkedIn via Tavily (ADR 0061): one search per query on linkedin.com, all sent to review. */
  @Validated
  @ConfigurationProperties("helper2.scout.linkedin")
  record LinkedIn(@NotNull Duration interval, @NotNull List<String> queries) implements Scheduled {

    LinkedIn {
      queries = clean(queries);
    }
  }

  /**
   * Upcoming AI events from the live web (ADR 0067): Tavily searches, Extract of at most [maxPages]
   * pages a pass, and at most [readerDailyCalls] model calls a UTC day to read them.
   */
  @Validated
  @ConfigurationProperties("helper2.scout.live-web-events")
  record LiveWebEvents(
      @NotNull Duration interval,
      @NotNull List<String> queries,
      @Positive int maxPages,
      @PositiveOrZero int readerDailyCalls,
      @Positive int readMaxTokens)
      implements Scheduled {

    LiveWebEvents {
      queries = clean(queries);
    }
  }

  /**
   * The Tavily credits the Scout's searches (every discovery category and LinkedIn, ADR 0051 and
   * 0061) may spend per UTC day, shared by all of them.
   */
  @Validated
  @ConfigurationProperties("helper2.scout")
  record ScoutCredits(@PositiveOrZero long dailyCredits) {}

  /**
   * Tavily discovery (ADR 0051): what every category's collector shares. A category's own options
   * (helper2.categories) override the interval and the score floor and add to the excluded domains.
   */
  @Validated
  @ConfigurationProperties("helper2.scout.discovery")
  record Discovery(
      @NotNull Duration interval,
      @NotNull @Pattern(regexp = "basic|advanced") String searchDepth,
      @Positive @Max(20) int maxResults,
      @DecimalMin("0") @DecimalMax("1") double minScore,
      @NotNull List<String> excludeDomains)
      implements Scheduled {

    Discovery {
      excludeDomains = clean(excludeDomains);
    }
  }

  /**
   * GitHub awesome lists (ADR 0020, 0055) and their deep search (ADR 0058): how a run reads them,
   * how far it follows nested lists and how many product links it proposes for review.
   */
  @Validated
  @ConfigurationProperties("helper2.scout.github-awesome")
  record GitHubAwesome(
      @NotNull Duration interval,
      @NotNull List<String> topicQueries,
      @Positive int maxLists,
      @PositiveOrZero int minListStars,
      @Positive int maxEntriesPerPass,
      @Positive int maxEntriesPerRun,
      @PositiveOrZero int minRepoStars,
      @Positive int classifyBatch,
      @Positive int classifyMaxTokens,
      @PositiveOrZero int classifierDailyCalls,
      @NotNull Duration maxRunTime,
      @NotNull DataSize maxReadmeBytesPerRun,
      @NotNull @Valid Nested nested,
      @NotNull @Valid Links links)
      implements Scheduled {

    GitHubAwesome {
      topicQueries = clean(topicQueries);
    }

    @AssertTrue(message = "must be positive")
    boolean isMaxRunTimePositive() {
      return maxRunTime == null || (!maxRunTime.isNegative() && !maxRunTime.isZero());
    }

    /** Lists linked from lists: steps from a seed (0 = none) and lists tried per run. */
    record Nested(@PositiveOrZero @Max(5) int maxDepth, @PositiveOrZero int maxListsPerRun) {}

    /**
     * Links to product sites: new review items per UTC day, links kept per pass, and the domains
     * that are never a product's own site, on top of discovery's (helper2.scout.discovery).
     */
    record Links(
        @PositiveOrZero int perDay,
        @PositiveOrZero int maxPerPass,
        @NotNull List<String> excludeDomains) {

      Links {
        excludeDomains = clean(excludeDomains);
      }
    }
  }

  @Validated
  @ConfigurationProperties("helper2.scout.hf-provider-orgs")
  record HfProviderOrgs(
      @NotNull Duration interval, @NotNull List<String> orgs, @Positive int perOrg)
      implements Scheduled {

    HfProviderOrgs {
      orgs = clean(orgs);
    }
  }

  @Validated
  @ConfigurationProperties("helper2.scout.ai-deadlines")
  record AiDeadlines(@NotNull Duration interval) implements Scheduled {}

  @Validated
  @ConfigurationProperties("helper2.scout.mcp-registry")
  record McpRegistry(@NotNull Duration interval) implements Scheduled {}

  private static List<String> clean(List<String> values) {
    return values == null
        ? null
        : values.stream().map(String::strip).filter(v -> !v.isEmpty()).toList();
  }
}
