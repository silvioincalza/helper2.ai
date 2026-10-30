package ai.helper2.sources;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.platform.http.ObservedHttp;
import ai.helper2.platform.resilience.Resilience;
import ai.helper2.platform.llm.DailyBudget;
import ai.helper2.scout.CollectedBatch;
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

/**
 * Opt-in: real GitHub (search, raw README, GraphQL) and real Nemotron Nano on Token Factory.
 * H2_GITHUB_TOKEN=$(gh auth token) H2_NEBIUS_API_KEY=... ./gradlew liveTest --tests '*GitHubAwesomeLiveTest'
 * Costs about 4 GitHub calls and 2 Nano calls.
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "H2_GITHUB_TOKEN", matches = ".+")
@EnabledIfEnvironmentVariable(named = "H2_NEBIUS_API_KEY", matches = ".+")
class GitHubAwesomeLiveTest {

    static final String NANO = System.getenv().getOrDefault("H2_NEBIUS_FAST_MODEL", "nvidia/NVIDIA-Nemotron-3-Nano-30B-A3B");

    @Test
    void oneListThroughTheWholePipelineWithTheFastModel() {
        OpenAiChatOptions options = OpenAiChatOptions.builder()
            .baseUrl("https://api.tokenfactory.nebius.com/v1")
            .apiKey(System.getenv("H2_NEBIUS_API_KEY"))
            .model(NANO)
            .build();
        ChatModel chat = OpenAiChatModel.builder().options(options).build();
        RepoClassifier nano = new SpringAiRepoClassifier(chat, NANO, 8192, new DailyBudget(300, Clock.systemUTC(), "classifier"),
            ai.helper2.platform.llm.ModelCallMetrics.none());
        GitHubAwesomeCollector collector = new GitHubAwesomeCollector(
            new SourceSettings(true, "https://api.github.com", System.getenv("H2_GITHUB_TOKEN"), Duration.ofSeconds(30), Duration.ZERO),
            ObservedHttp.restClientBuilder(Duration.ofSeconds(30), io.micrometer.observation.ObservationRegistry.NOOP), Clock.systemUTC(), Resilience.NONE, new RunInterval(Duration.ofDays(1)),
            List.of("topic:awesome-list topic:llm"), new GitHubAwesomeCollector.Limits(1, 1000, 80, 50, 50, 25), nano);

        List<String> lists = collector.discoverLists();
        long started = System.nanoTime();
        CollectedBatch batch = collector.collect(null, null, 50);
        long ms = (System.nanoTime() - started) / 1_000_000;

        System.out.printf("list %s: %d AI resources kept in %d ms%n", lists, batch.getHits().size(), ms);
        batch.getHits().forEach(h -> System.out.printf("  %-50s %-10s %s%n", h.getExternalId(), h.getType(),
            h.getPopularity().isEmpty() ? "" : h.getPopularity().getFirst().getValue()));
        assertThat(lists).singleElement().satisfies(l -> assertThat(l.toLowerCase()).contains("awesome"));
        assertThat(batch.getHits()).isNotEmpty().allSatisfy(h -> {
            assertThat(h.getUrl().getHost()).isEqualTo("github.com");
            assertThat(h.getPopularity()).isNotEmpty();
        });
        assertThat(batch.getNextCursor()).isNotNull();

        // The model itself, not the heuristic: it must answer every id.
        Map<String, RepoClassifier.Verdict> direct = nano.classify(List.of(
            new RepoClassifier.Facts("r0", "nodejs/node", "Node.js JavaScript runtime", List.of("javascript"), "Runtimes"),
            new RepoClassifier.Facts("r1", "modelcontextprotocol/servers", "Model Context Protocol Servers", List.of("mcp"), "MCP")));
        System.out.println("direct: " + direct);
        assertThat(direct).containsKeys("r0", "r1");
        assertThat(direct.get("r0").ai()).isFalse();
        assertThat(direct.get("r1").ai()).isTrue();
    }

}
