package ai.searchavo.sources.github.scout;

import static org.assertj.core.api.Assertions.assertThat;

import ai.searchavo.platform.llm.DailyBudget;
import ai.searchavo.platform.resilience.Resilience;
import ai.searchavo.scout.CollectedBatch;
import ai.searchavo.scout.InMemoryListSeedStore;
import ai.searchavo.scout.SeedDiscoveryHosts;
import ai.searchavo.sources.SourceFixtures;
import ai.searchavo.sources.classifier.RepoClassifier;
import ai.searchavo.sources.classifier.SpringAiRepoClassifier;
import ai.searchavo.sources.support.RunInterval;
import ai.searchavo.sources.support.SourceSettings;
import ai.searchavo.testing.TestHttp;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

@Tag("live")
@EnabledIfEnvironmentVariable(named = "SA_GITHUB_TOKEN", matches = ".+")
@EnabledIfEnvironmentVariable(named = "SA_NEBIUS_API_KEY", matches = ".+")
class GitHubAwesomeLiveTest {

  static final String NANO =
      System.getenv().getOrDefault("SA_NEBIUS_FAST_MODEL", "nvidia/Nemotron-3_5-Lightning");

  @Test
  void oneListThroughTheWholePipelineWithTheFastModel() {
    OpenAiChatOptions options =
        OpenAiChatOptions.builder()
            .baseUrl("https://api.tokenfactory.nebius.com/v1")
            .apiKey(System.getenv("SA_NEBIUS_API_KEY"))
            .model(NANO)
            .build();
    ChatModel chat = OpenAiChatModel.builder().options(options).build();
    RepoClassifier nano =
        new SpringAiRepoClassifier(
            chat,
            NANO,
            8192,
            new DailyBudget(300, Clock.systemUTC(), "classifier"),
            ai.searchavo.platform.llm.ModelCallMetrics.none(),
            Resilience.NONE);
    GitHubAwesomeCollector collector =
        new GitHubAwesomeCollector(
            new SourceSettings(
                true,
                "https://api.github.com",
                System.getenv("SA_GITHUB_TOKEN"),
                Duration.ofSeconds(30),
                Duration.ZERO,
                null),
            TestHttp.restClientBuilder(
                Duration.ofSeconds(30), io.micrometer.observation.ObservationRegistry.NOOP),
            Clock.systemUTC(),
            Resilience.NONE,
            new RunInterval(Duration.ofDays(1)),
            List.of("topic:awesome-list topic:llm"),
            new GitHubAwesomeCollector.Limits(1, 1000, 80, 50, 50, 25),
            new GitHubAwesomeCollector.Deep(
                1, 3, Duration.ofMinutes(5), 20_000_000L, 50, 0, 0, 0, 0, List.of("youtube.com")),
            nano,
            ai.searchavo.shared.CategoryMatcher.NONE,
            new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
            SeedDiscoveryHosts.store(),
            new InMemoryListSeedStore(),
            SourceFixtures.SHADOW_LIBRARIES,
            GitHubAwesomeCollectorTest.NO_LINK_RULES);

    List<String> lists = collector.discoverLists();
    long started = System.nanoTime();
    CollectedBatch batch = collector.collect(null, null, 50);
    long ms = (System.nanoTime() - started) / 1_000_000;

    System.out.printf(
        "list %s: %d AI resources kept in %d ms%n", lists, batch.getHits().size(), ms);
    batch
        .getHits()
        .forEach(h ->
            System.out.printf(
                "  %-50s %-10s %s%n",
                h.getExternalId(),
                h.getType(),
                h.getPopularity().isEmpty() ? "" : h.getPopularity().getFirst().getValue()));
    assertThat(lists)
        .singleElement()
        .satisfies(l -> assertThat(l.toLowerCase()).contains("awesome"));
    assertThat(batch.getHits())
        .isNotEmpty()
        .allSatisfy(h -> {
          assertThat(h.getUrl().getHost()).isEqualTo("github.com");
          assertThat(h.getPopularity()).isNotEmpty();
        });
    assertThat(batch.getNextCursor()).isNotNull();

    Map<String, RepoClassifier.Verdict> direct =
        nano.classify(
            List.of(
                new RepoClassifier.Facts(
                    "r0",
                    "nodejs/node",
                    "Node.js JavaScript runtime",
                    List.of("javascript"),
                    "Runtimes"),
                new RepoClassifier.Facts(
                    "r1",
                    "modelcontextprotocol/servers",
                    "Model Context Protocol Servers",
                    List.of("mcp"),
                    "MCP")));
    System.out.println("direct: " + direct);
    assertThat(direct).containsKeys("r0", "r1");
    assertThat(direct.get("r0").ai()).isFalse();
    assertThat(direct.get("r1").ai()).isTrue();
  }
}
