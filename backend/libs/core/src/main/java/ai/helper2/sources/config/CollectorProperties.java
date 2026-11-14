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
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

public final class CollectorProperties {

  private CollectorProperties() {}

  public interface Scheduled {
    Duration interval();

    @AssertTrue(message = "must be zero or positive")
    default boolean isIntervalZeroOrPositive() {
      return interval() == null || !interval().isNegative();
    }
  }

  @Validated
  @ConfigurationProperties("helper2.scout.hf-trending")
  public record HfTrending(@NotNull Duration interval) implements Scheduled {}

  @Validated
  @ConfigurationProperties("helper2.scout.live-web")
  public record LiveWebScout(@NotNull Duration interval, @NotNull List<String> queries)
      implements Scheduled {

    public LiveWebScout {
      queries = clean(queries);
    }
  }

  @Validated
  @ConfigurationProperties("helper2.scout.courses")
  public record Courses(
      @NotNull Duration interval, @NotNull String query, @NotNull List<String> platforms)
      implements Scheduled {

    public Courses {
      platforms = clean(platforms);
    }
  }

  @Validated
  @ConfigurationProperties("helper2.scout.linkedin")
  public record LinkedIn(@NotNull Duration interval, @NotNull List<String> queries)
      implements Scheduled {

    public LinkedIn {
      queries = clean(queries);
    }
  }

  @Validated
  @ConfigurationProperties("helper2.scout.live-web-events")
  public record LiveWebEvents(
      @NotNull Duration interval,
      @NotNull List<String> queries,
      @Positive int maxPages,
      @PositiveOrZero int readerDailyCalls,
      @Positive int readMaxTokens)
      implements Scheduled {

    public LiveWebEvents {
      queries = clean(queries);
    }
  }

  @Validated
  @ConfigurationProperties("helper2.scout")
  public record ScoutCredits(@PositiveOrZero long dailyCredits) {}

  @Validated
  @ConfigurationProperties("helper2.scout.discovery")
  public record Discovery(
      @NotNull Duration interval,
      @NotNull @Pattern(regexp = "basic|advanced") String searchDepth,
      @Positive @Max(20) int maxResults,
      @DecimalMin("0") @DecimalMax("1") double minScore,
      @NotNull List<String> excludeDomains)
      implements Scheduled {

    public Discovery {
      excludeDomains = clean(excludeDomains);
    }
  }

  @Validated
  @ConfigurationProperties("helper2.scout.github-awesome")
  public record GitHubAwesome(
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

    public GitHubAwesome {
      topicQueries = clean(topicQueries);
    }

    @AssertTrue(message = "must be positive")
    boolean isMaxRunTimePositive() {
      return maxRunTime == null || (!maxRunTime.isNegative() && !maxRunTime.isZero());
    }

    public record Nested(
        @PositiveOrZero @Max(5) int maxDepth, @PositiveOrZero int maxListsPerRun) {}

    public record Links(
        @PositiveOrZero int perDay,
        @PositiveOrZero int maxPerPass,
        @NotNull List<String> excludeDomains) {

      public Links {
        excludeDomains = clean(excludeDomains);
      }
    }
  }

  @Validated
  @ConfigurationProperties("helper2.scout.github-topic-leaders")
  public record GitHubTopicLeaders(
      @NotNull Duration interval,
      @NotNull List<String> topics,
      @PositiveOrZero int minStars,
      @Positive @Max(100) int perTopic,
      @PositiveOrZero int perDay,
      @PositiveOrZero @Max(1000) int indexPerTopic,
      @NotNull Duration searchPause)
      implements Scheduled {

    public GitHubTopicLeaders {
      topics = clean(topics);
    }
  }

  @Validated
  @ConfigurationProperties("helper2.scout.github-sdks")
  public record GitHubSdks(
      @NotNull Duration interval,
      @NotNull List<String> topics,
      @NotNull List<@Pattern(regexp = "[A-Za-z#+ ]{1,20}:[0-9]{1,7}") String> languages,
      @NotNull List<String> orgs,
      @PositiveOrZero int orgMinStars,
      @Positive @Max(100) int perQuery,
      @Positive @Max(10) int pagesPerQuery,
      @Positive int queriesPerRun,
      @PositiveOrZero int rateLimitReserve,
      @NotNull Duration searchPause,
      @PositiveOrZero int perDay,
      @NotNull String depsDevUrl,
      @Positive @Max(10) int packagesPerRepo)
      implements Scheduled {

    @AssertTrue(message = "must be zero or positive")
    boolean isSearchPauseZeroOrPositive() {
      return searchPause == null || !searchPause.isNegative();
    }

    public GitHubSdks {
      topics = clean(topics);
      languages = clean(languages);
      orgs = clean(orgs);
    }
  }

  @Validated
  @ConfigurationProperties("helper2.scout.hf-provider-orgs")
  public record HfProviderOrgs(
      @NotNull Duration interval, @NotNull List<String> orgs, @Positive int perOrg)
      implements Scheduled {

    public HfProviderOrgs {
      orgs = clean(orgs);
    }
  }

  @Validated
  @ConfigurationProperties("helper2.scout.ai-deadlines")
  public record AiDeadlines(@NotNull Duration interval) implements Scheduled {}

  @Validated
  @ConfigurationProperties("helper2.scout.mcp-registry")
  public record McpRegistry(@NotNull Duration interval, @PositiveOrZero int itemsPerRun)
      implements Scheduled {}

  @Validated
  @ConfigurationProperties("helper2.scout.pypi")
  public record Pypi(
      @NotNull Duration interval,
      @Nullable String project,
      @PositiveOrZero int candidatesPerDay,
      @Positive long maxBytesBilled)
      implements Scheduled {}

  @Validated
  @ConfigurationProperties("helper2.scout.news-feeds")
  public record NewsFeeds(@NotNull Duration interval) implements Scheduled {}

  private static List<String> clean(List<String> values) {
    return values == null
        ? null
        : values.stream().map(String::strip).filter(v -> !v.isEmpty()).toList();
  }
}
