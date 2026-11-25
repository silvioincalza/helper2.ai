package ai.searchavo.sources.aideadlines;

import static ai.searchavo.sources.SourceFixtures.live;
import static ai.searchavo.sources.SourceFixtures.liveHttp;
import static org.assertj.core.api.Assertions.assertThat;

import ai.searchavo.platform.resilience.Resilience;
import ai.searchavo.scout.CollectedBatch;
import ai.searchavo.shared.ResourceType;
import ai.searchavo.sources.support.RunInterval;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@Tag("live")
@EnabledIfEnvironmentVariable(named = "SA_GITHUB_TOKEN", matches = ".+")
class AiDeadlinesCollectorLiveTest {

  @Test
  void conferencesWithAnUpcomingEditionBecomeEvents() {
    AiDeadlinesCollector collector =
        new AiDeadlinesCollector(
            live("https://api.github.com", "SA_GITHUB_TOKEN"),
            liveHttp(),
            liveHttp(),
            Clock.systemUTC(),
            Resilience.NONE,
            new RunInterval(Duration.ZERO));

    CollectedBatch batch = collector.collect(null, null, 500);

    assertThat(batch.getHits())
        .isNotEmpty()
        .allSatisfy(h -> assertThat(h.getType()).isEqualTo(ResourceType.EVENT));
  }
}
