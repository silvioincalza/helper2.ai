package ai.helper2.sources.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/** Each collector's settings bind from helper2.scout.<collector> with the documented defaults; invalid values stop startup. */
class CollectorPropertiesTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({CollectorProperties.HfTrending.class, CollectorProperties.TavilyScout.class,
        CollectorProperties.Courses.class, CollectorProperties.GitHubAwesome.class, CollectorProperties.HfProviderOrgs.class,
        CollectorProperties.AiDeadlines.class, CollectorProperties.McpRegistry.class})
    static class Binding {
    }

    final ApplicationContextRunner context = new ApplicationContextRunner().withUserConfiguration(Binding.class);

    @Test
    void withoutConfigurationEveryCollectorGetsItsDefaults() {
        context.run(c -> {
            assertThat(c).hasNotFailed();
            assertThat(c.getBean(CollectorProperties.HfTrending.class)).isEqualTo(new CollectorProperties.HfTrending(Duration.ofDays(1), 20));
            assertThat(c.getBean(CollectorProperties.TavilyScout.class)).isEqualTo(new CollectorProperties.TavilyScout(Duration.ofDays(5), List.of()));
            assertThat(c.getBean(CollectorProperties.Courses.class)).isEqualTo(new CollectorProperties.Courses(Duration.ofDays(7),
                "new artificial intelligence course or certification", List.of()));
            assertThat(c.getBean(CollectorProperties.GitHubAwesome.class)).isEqualTo(new CollectorProperties.GitHubAwesome(Duration.ofDays(1),
                List.of("topic:awesome-list topic:llm"), 10, 1000, 1000, 200, 50, 25, 8192, 300));
            assertThat(c.getBean(CollectorProperties.HfProviderOrgs.class)).isEqualTo(new CollectorProperties.HfProviderOrgs(Duration.ofDays(1),
                List.of("meta-llama", "nvidia", "mistralai", "deepseek-ai", "Qwen", "google", "openai", "xai-org"), 20));
            assertThat(c.getBean(CollectorProperties.AiDeadlines.class).interval()).isEqualTo(Duration.ofDays(1));
            assertThat(c.getBean(CollectorProperties.McpRegistry.class).interval()).isEqualTo(Duration.ofHours(1));
        });
    }

    @Test
    void foldedListsAreTrimmedAndBlankEntriesDropped() {
        context.withPropertyValues("helper2.scout.tavily.queries=new AI tool launched,\n  new MCP server released, ,",
                "helper2.scout.courses.platforms= edx.org=edX , coursera.org=Coursera")
            .run(c -> {
                assertThat(c.getBean(CollectorProperties.TavilyScout.class).queries()).containsExactly("new AI tool launched", "new MCP server released");
                assertThat(c.getBean(CollectorProperties.Courses.class).platforms()).containsExactly("edx.org=edX", "coursera.org=Coursera");
            });
    }

    @Test
    void anInvalidValueStopsStartupAndNamesTheProperty() {
        context.withPropertyValues("helper2.scout.github-awesome.max-lists=0").run(c -> {
            assertThat(c).hasFailed();
            assertThat(c.getStartupFailure()).rootCause().isInstanceOf(BindValidationException.class)
                .hasMessageContaining("helper2.scout.github-awesome.maxLists");
        });
    }

    @Test
    void aNegativeIntervalStopsStartup() {
        context.withPropertyValues("helper2.scout.mcp-registry.interval=-PT1H").run(c -> assertThat(c).hasFailed());
    }
}
