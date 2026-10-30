package ai.helper2.sources;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.platform.http.ObservedHttp;
import ai.helper2.platform.resilience.Resilience;
import ai.helper2.scout.CollectedBatch;
import ai.helper2.search.SourceHit;
import ai.helper2.shared.ResourceType;
import ai.helper2.testing.FixtureServer;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class HuggingFaceTrendingCollectorTest {

    static final String TRENDING = """
        [{"id":"deepseek-ai/DeepSeek-V4.1-Flash-1017","likes":2971,"downloads":85275,
          "tags":["transformers","text-generation","license:mit","region:us"],"pipeline_tag":"image-text-to-text",
          "createdAt":"2026-10-17T02:40:38.000Z"},
         {"id":"someone/tiny-experiment","likes":3,"downloads":10,"tags":["license:apache-2.0"],"pipeline_tag":"text-generation",
          "createdAt":"2026-10-21T00:00:00.000Z"},
         {"likes":500}]""";

    final FixtureServer server = new FixtureServer();

    @AfterEach
    void stop() {
        server.close();
    }

    HuggingFaceTrendingCollector collector(Duration interval) {
        return new HuggingFaceTrendingCollector(new SourceSettings(true, server.baseUrl(), "", Duration.ofSeconds(2), Duration.ZERO),
            ObservedHttp.restClientBuilder(Duration.ofSeconds(2), io.micrometer.observation.ObservationRegistry.NOOP), GitHubSourceTest.CLOCK, Resilience.NONE, new RunInterval(interval), 20);
    }

    @Test
    void collectsTrendingModelsAboveTheLikeThresholdWithDeterministicCategories() {
        server.route("/api/models", FixtureServer.Reply.ok(TRENDING));
        CollectedBatch batch = collector(Duration.ofDays(1)).collect(null, null, 500);

        assertThat(server.requests.getFirst()).contains("sort=trendingScore").contains("direction=-1").contains("limit=100");
        assertThat(batch.getHits()).extracting(SourceHit::getExternalId).containsExactly("deepseek-ai/DeepSeek-V4.1-Flash-1017");
        SourceHit hit = batch.getHits().getFirst();
        assertThat(hit.getSourceId()).isEqualTo("hf-trending");
        assertThat(hit.getType()).isEqualTo(ResourceType.MODEL);
        assertThat(hit.getLicense()).isEqualTo("MIT");
        assertThat(hit.getTags()).contains("image-text-to-text");
        assertThat(batch.getNextCursor()).isNull();
        assertThat(batch.getCheckpoint()).isEqualTo(GitHubSourceTest.CLOCK.instant().toString());
    }

    @Test
    void doesNotCallTheHubBeforeTheIntervalHasPassed() {
        String lastPass = GitHubSourceTest.CLOCK.instant().minus(Duration.ofHours(2)).toString();
        CollectedBatch batch = collector(Duration.ofDays(1)).collect(lastPass, null, 500);
        assertThat(batch.getNotDue()).isTrue();
        assertThat(server.requests).isEmpty();
    }
}
