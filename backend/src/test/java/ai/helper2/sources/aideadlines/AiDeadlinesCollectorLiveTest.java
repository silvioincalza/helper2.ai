package ai.helper2.sources.aideadlines;

import static ai.helper2.sources.SourceFixtures.live;
import static ai.helper2.sources.SourceFixtures.liveHttp;
import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.platform.resilience.Resilience;
import ai.helper2.scout.CollectedBatch;
import ai.helper2.shared.ResourceType;
import ai.helper2.sources.support.RunInterval;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Opt-in: huggingface/ai-deadlines through the GitHub contents API and raw.githubusercontent.com.
 * H2_GITHUB_TOKEN=$(gh auth token) ./gradlew liveTest. One contents call and about 70 raw files. The token is
 * required here: anonymous contents calls share 60 per hour per IP address and fail on shared runners.
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "H2_GITHUB_TOKEN", matches = ".+")
class AiDeadlinesCollectorLiveTest {

    @Test
    void conferencesWithAnUpcomingEditionBecomeEvents() {
        AiDeadlinesCollector collector = new AiDeadlinesCollector(live("https://api.github.com", "H2_GITHUB_TOKEN"), liveHttp(),
            Clock.systemUTC(), Resilience.NONE, new RunInterval(Duration.ZERO));

        CollectedBatch batch = collector.collect(null, null, 500);

        assertThat(batch.getHits()).isNotEmpty().allSatisfy(h -> assertThat(h.getType()).isEqualTo(ResourceType.EVENT));
    }
}
