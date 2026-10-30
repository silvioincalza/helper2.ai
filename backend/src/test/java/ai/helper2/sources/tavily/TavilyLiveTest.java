package ai.helper2.sources.tavily;

import static ai.helper2.sources.SourceFixtures.live;
import static ai.helper2.sources.SourceFixtures.liveHttp;
import static ai.helper2.sources.SourceFixtures.query;
import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.platform.resilience.Resilience;
import ai.helper2.scout.CollectedBatch;
import ai.helper2.search.SourceHit;
import ai.helper2.sources.support.RunInterval;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Opt-in: the real Tavily Search API, one test per adapter. H2_TAVILY_API_KEY=... ./gradlew liveTest
 * Three credits per run (search_depth basic, one query each).
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "H2_TAVILY_API_KEY", matches = ".+")
class TavilyLiveTest {

    static final String API = "https://api.tavily.com";

    @Test
    void searchSourceFindsPagesWithTheirAnswerSnippet() {
        TavilySource source = new TavilySource(live(API, "H2_TAVILY_API_KEY"), liveHttp(), Clock.systemUTC());

        List<SourceHit> hits = source.search(query("new open-source LLM released"));

        assertThat(hits).isNotEmpty().allSatisfy(h -> assertThat(h.getUrl().getScheme()).isEqualTo("https"));
    }

    @Test
    void discoveryCollectorRunsOneNewsQuery() {
        TavilyCollector collector = new TavilyCollector(live(API, "H2_TAVILY_API_KEY"), liveHttp(), Clock.systemUTC(), Resilience.NONE,
            new RunInterval(Duration.ZERO), List.of("new MCP server released"));

        CollectedBatch batch = collector.collect(null, null, 20);

        assertThat(batch.getHits()).isNotEmpty();
        assertThat(batch.getCheckpoint()).isNotNull();
    }

    @Test
    void courseCollectorKeepsOnlyPagesOnThePlatform() {
        CourseCollector collector = new CourseCollector(live(API, "H2_TAVILY_API_KEY"), liveHttp(), Clock.systemUTC(), Resilience.NONE,
            new RunInterval(Duration.ZERO), "new artificial intelligence course or certification", List.of("deeplearning.ai=DeepLearning.AI"));

        CollectedBatch batch = collector.collect(null, null, 10);

        // New pages are not guaranteed every month; a complete pass is, and nothing off the platform.
        assertThat(batch.getHits()).isEmpty();
        assertThat(batch.getCheckpoint()).isNotNull();
        assertThat(batch.getCandidates()).allSatisfy(c -> assertThat(c.getUrl().getValue()).contains("deeplearning.ai"));
    }
}
