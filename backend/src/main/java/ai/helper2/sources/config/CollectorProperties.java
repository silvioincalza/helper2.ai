package ai.helper2.sources.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * The settings of each Scout collector, bound from {@code helper2.scout.<collector>} (environment variables in
 * application.yaml). Invalid values stop startup with the name of the property. Lists are trimmed and blank
 * entries dropped, so folded YAML lists can span lines.
 */
final class CollectorProperties {

    private CollectorProperties() {
    }

    /** Every collector has a run interval (RunInterval): how long after a complete pass the next one is due. */
    interface Scheduled {
        Duration interval();

        @AssertTrue(message = "must be zero or positive")
        default boolean isIntervalZeroOrPositive() {
            return interval() == null || !interval().isNegative();
        }
    }

    @Validated
    @ConfigurationProperties("helper2.scout.hf-trending")
    record HfTrending(@DefaultValue("P1D") @NotNull Duration interval, @DefaultValue("20") @PositiveOrZero int minLikes) implements Scheduled {
    }

    @Validated
    @ConfigurationProperties("helper2.scout.tavily")
    record TavilyScout(@DefaultValue("P5D") @NotNull Duration interval, @DefaultValue List<String> queries) implements Scheduled {

        TavilyScout {
            queries = clean(queries);
        }
    }

    @Validated
    @ConfigurationProperties("helper2.scout.courses")
    record Courses(@DefaultValue("P7D") @NotNull Duration interval,
                   @DefaultValue("new artificial intelligence course or certification") @NotNull String query,
                   @DefaultValue List<String> platforms) implements Scheduled {

        Courses {
            platforms = clean(platforms);
        }
    }

    @Validated
    @ConfigurationProperties("helper2.scout.github-awesome")
    record GitHubAwesome(@DefaultValue("P1D") @NotNull Duration interval,
                         @DefaultValue("topic:awesome-list topic:llm") List<String> topicQueries,
                         @DefaultValue("10") @Positive int maxLists,
                         @DefaultValue("1000") @PositiveOrZero int minListStars,
                         @DefaultValue("1000") @Positive int maxEntriesPerPass,
                         @DefaultValue("200") @Positive int maxEntriesPerRun,
                         @DefaultValue("50") @PositiveOrZero int minRepoStars,
                         @DefaultValue("25") @Positive int classifyBatch,
                         @DefaultValue("8192") @Positive int classifyMaxTokens,
                         @DefaultValue("300") @PositiveOrZero int classifierDailyCalls) implements Scheduled {

        GitHubAwesome {
            topicQueries = clean(topicQueries);
        }
    }

    @Validated
    @ConfigurationProperties("helper2.scout.hf-provider-orgs")
    record HfProviderOrgs(@DefaultValue("P1D") @NotNull Duration interval,
                          @DefaultValue({"meta-llama", "nvidia", "mistralai", "deepseek-ai", "Qwen", "google", "openai", "xai-org"}) List<String> orgs,
                          @DefaultValue("20") @Positive int perOrg) implements Scheduled {

        HfProviderOrgs {
            orgs = clean(orgs);
        }
    }

    @Validated
    @ConfigurationProperties("helper2.scout.ai-deadlines")
    record AiDeadlines(@DefaultValue("P1D") @NotNull Duration interval) implements Scheduled {
    }

    @Validated
    @ConfigurationProperties("helper2.scout.mcp-registry")
    record McpRegistry(@DefaultValue("PT1H") @NotNull Duration interval) implements Scheduled {
    }

    private static List<String> clean(List<String> values) {
        return values == null ? List.of() : values.stream().map(String::strip).filter(v -> !v.isEmpty()).toList();
    }
}
