package ai.helper2.sources.config;

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
  @ConfigurationProperties("helper2.scout.tavily")
  record TavilyScout(@NotNull Duration interval, @NotNull List<String> queries)
      implements Scheduled {

    TavilyScout {
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

  /**
   * Tavily discovery (ADR 0051): what every category's collector shares. A category's own options
   * (helper2.categories) override the interval and the score floor and add to the excluded domains.
   * {@code dailyCredits} caps the Tavily credits all discovery searches spend per UTC day.
   */
  @Validated
  @ConfigurationProperties("helper2.scout.discovery")
  record Discovery(
      @NotNull Duration interval,
      @NotNull @Pattern(regexp = "basic|advanced") String searchDepth,
      @Positive @Max(20) int maxResults,
      @DecimalMin("0") @DecimalMax("1") double minScore,
      @PositiveOrZero long dailyCredits,
      @NotNull List<String> excludeDomains)
      implements Scheduled {

    Discovery {
      excludeDomains = clean(excludeDomains);
    }
  }

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
      @PositiveOrZero int classifierDailyCalls)
      implements Scheduled {

    GitHubAwesome {
      topicQueries = clean(topicQueries);
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
