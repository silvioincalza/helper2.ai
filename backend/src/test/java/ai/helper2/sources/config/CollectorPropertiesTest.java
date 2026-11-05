package ai.helper2.sources.config;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.testing.ConfigBinding;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * Each collector's settings bind from helper2.scout.<collector> with the defaults of
 * application.yaml; H2_SCOUT_* variables reach them, and invalid values stop startup.
 */
class CollectorPropertiesTest {

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties({
    CollectorProperties.HfTrending.class,
    CollectorProperties.TavilyScout.class,
    CollectorProperties.Courses.class,
    CollectorProperties.ScoutCredits.class,
    CollectorProperties.Discovery.class,
    CollectorProperties.GitHubAwesome.class,
    CollectorProperties.HfProviderOrgs.class,
    CollectorProperties.AiDeadlines.class,
    CollectorProperties.McpRegistry.class
  })
  static class Binding {}

  final ApplicationContextRunner context = ConfigBinding.of(Binding.class);

  @Test
  void theYamlDefaultsBind() {
    context.run(c -> {
      assertThat(c).hasNotFailed();
      CollectorProperties.Discovery discovery = c.getBean(CollectorProperties.Discovery.class);
      assertThat(discovery.interval()).isEqualTo(Duration.ofDays(7));
      assertThat(discovery.searchDepth()).isEqualTo("advanced");
      assertThat(discovery.maxResults()).isEqualTo(10);
      assertThat(c.getBean(CollectorProperties.ScoutCredits.class).dailyCredits()).isEqualTo(40);
      assertThat(discovery.excludeDomains()).contains("producthunt.com", "alternativeto.net");
      assertThat(c.getBean(CollectorProperties.HfTrending.class))
          .isEqualTo(new CollectorProperties.HfTrending(Duration.ofDays(1), 20));
      assertThat(c.getBean(CollectorProperties.TavilyScout.class))
          .isEqualTo(
              new CollectorProperties.TavilyScout(
                  Duration.ofDays(5),
                  List.of(
                      "new AI tool launched",
                      "new open-source LLM released",
                      "new MCP server released",
                      "new AI agent framework released",
                      "new model released OpenAI Anthropic Google Gemini",
                      "new model released xAI Grok Meta Llama Mistral",
                      "new model released NVIDIA Nemotron DeepSeek Qwen")));
      CollectorProperties.Courses courses = c.getBean(CollectorProperties.Courses.class);
      assertThat(courses.interval()).isEqualTo(Duration.ofDays(7));
      assertThat(courses.query()).isEqualTo("new artificial intelligence course or certification");
      assertThat(courses.platforms())
          .hasSize(10)
          .startsWith("coursera.org=Coursera", "oreilly.com=O'Reilly")
          .endsWith("nvidia.com=NVIDIA");
      assertThat(c.getBean(CollectorProperties.GitHubAwesome.class))
          .isEqualTo(
              new CollectorProperties.GitHubAwesome(
                  Duration.ofDays(1),
                  List.of(
                      "topic:awesome-list topic:llm",
                      "topic:awesome topic:llm",
                      "topic:awesome-list topic:artificial-intelligence",
                      "topic:awesome-list topic:ai-agents",
                      "topic:awesome topic:mcp",
                      "topic:awesome topic:generative-ai"),
                  10,
                  1000,
                  1000,
                  1000,
                  50,
                  25,
                  8192,
                  300));
      assertThat(c.getBean(CollectorProperties.HfProviderOrgs.class))
          .isEqualTo(
              new CollectorProperties.HfProviderOrgs(
                  Duration.ofDays(1),
                  List.of(
                      "meta-llama",
                      "nvidia",
                      "mistralai",
                      "deepseek-ai",
                      "Qwen",
                      "google",
                      "openai",
                      "xai-org"),
                  20));
      assertThat(c.getBean(CollectorProperties.AiDeadlines.class).interval())
          .isEqualTo(Duration.ofDays(1));
      assertThat(c.getBean(CollectorProperties.McpRegistry.class).interval())
          .isEqualTo(Duration.ofHours(1));
    });
  }

  @Test
  void anH2VariableReachesItsField() {
    context
        .withSystemProperties("H2_SCOUT_AWESOME_PER_RUN=50", "H2_SCOUT_MCP_INTERVAL=PT2H")
        .run(c -> {
          assertThat(c.getBean(CollectorProperties.GitHubAwesome.class).maxEntriesPerRun())
              .isEqualTo(50);
          assertThat(c.getBean(CollectorProperties.McpRegistry.class).interval())
              .isEqualTo(Duration.ofHours(2));
        });
  }

  @Test
  void foldedListsAreTrimmedAndBlankEntriesDropped() {
    context
        .withPropertyValues(
            "helper2.scout.tavily.queries=new AI tool launched,\n  new MCP server released, ,",
            "helper2.scout.courses.platforms= edx.org=edX , coursera.org=Coursera")
        .run(c -> {
          assertThat(c.getBean(CollectorProperties.TavilyScout.class).queries())
              .containsExactly("new AI tool launched", "new MCP server released");
          assertThat(c.getBean(CollectorProperties.Courses.class).platforms())
              .containsExactly("edx.org=edX", "coursera.org=Coursera");
        });
  }

  @Test
  void anInvalidValueStopsStartupAndNamesTheProperty() {
    context
        .withPropertyValues("helper2.scout.github-awesome.max-lists=0")
        .run(c -> {
          assertThat(c).hasFailed();
          assertThat(c.getStartupFailure())
              .rootCause()
              .isInstanceOf(BindValidationException.class)
              .hasMessageContaining("helper2.scout.github-awesome.maxLists");
        });
  }

  @Test
  void aNegativeIntervalStopsStartup() {
    context.withSystemProperties("H2_SCOUT_MCP_INTERVAL=-PT1H").run(c -> assertThat(c).hasFailed());
  }

  @Test
  void anEmptyIntervalStopsStartup() {
    context
        .withPropertyValues("H2_SCOUT_HF_INTERVAL=")
        .run(c -> {
          assertThat(c).hasFailed();
          assertThat(c.getStartupFailure())
              .rootCause()
              .hasMessageContaining("helper2.scout.hf-trending.interval");
        });
  }
}
