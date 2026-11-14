package ai.helper2.sources.liveweb.scout;

import static ai.helper2.sources.SourceFixtures.live;
import static ai.helper2.sources.SourceFixtures.liveHttp;
import static ai.helper2.sources.SourceFixtures.query;
import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.platform.resilience.Resilience;
import ai.helper2.scout.CollectedBatch;
import ai.helper2.search.SourceHit;
import ai.helper2.sources.support.RunInterval;
import ai.helper2.sources.tavily.TavilySource;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@Tag("live")
@EnabledIfEnvironmentVariable(named = "H2_LIVE_WEB_API_KEY", matches = ".+")
class LiveWebScoutLiveTest {

  static final String API = "https://api.tavily.com";

  @Test
  void searchSourceFindsPagesWithTheirAnswerSnippet() {
    TavilySource source =
        new TavilySource(live(API, "H2_LIVE_WEB_API_KEY"), liveHttp(), Clock.systemUTC());

    List<SourceHit> hits = source.search(query("new open-source LLM released"));

    assertThat(hits)
        .isNotEmpty()
        .allSatisfy(h -> assertThat(h.getUrl().getScheme()).isEqualTo("https"));
  }

  @Test
  void discoveryCollectorRunsOneNewsQuery() {
    LiveWebNewsCollector collector =
        new LiveWebNewsCollector(
            live(API, "H2_LIVE_WEB_API_KEY"),
            liveHttp(),
            Clock.systemUTC(),
            Resilience.NONE,
            new RunInterval(Duration.ZERO),
            List.of("new MCP server released"),
            unlimitedCredits());

    CollectedBatch batch = collector.collect(null, null, 20);

    assertThat(batch.getHits()).isNotEmpty();
    assertThat(batch.getCheckpoint()).isNotNull();
  }

  @Test
  void courseCollectorKeepsOnlyPagesOnThePlatform() {
    CourseCollector collector =
        new CourseCollector(
            live(API, "H2_LIVE_WEB_API_KEY"),
            liveHttp(),
            Clock.systemUTC(),
            Resilience.NONE,
            new RunInterval(Duration.ZERO),
            "new artificial intelligence course or certification",
            List.of("deeplearning.ai=DeepLearning.AI"),
            unlimitedCredits());

    CollectedBatch batch = collector.collect(null, null, 10);

    assertThat(batch.getHits()).isEmpty();
    assertThat(batch.getCheckpoint()).isNotNull();
    assertThat(batch.getCandidates())
        .allSatisfy(c -> assertThat(c.getUrl().getValue()).contains("deeplearning.ai"));
  }

  private static ScoutLiveWebCredits unlimitedCredits() {
    Clock clock = Clock.systemUTC();
    return new ScoutLiveWebCredits(
        new DiscoveryCollectorTest.MapCounter(),
        new ai.helper2.platform.quota.QuotaKeys("", clock),
        Long.MAX_VALUE,
        new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
  }
}
