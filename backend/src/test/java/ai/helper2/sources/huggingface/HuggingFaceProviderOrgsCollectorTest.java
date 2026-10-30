package ai.helper2.sources.huggingface;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.platform.http.ObservedHttp;
import ai.helper2.platform.resilience.Resilience;
import ai.helper2.scout.CollectedBatch;
import ai.helper2.search.SourceHit;
import ai.helper2.sources.SourceFixtures;
import ai.helper2.sources.support.RunInterval;
import ai.helper2.sources.support.SourceSettings;
import ai.helper2.testing.FixtureServer;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class HuggingFaceProviderOrgsCollectorTest {

    static final String NEWEST = """
        [{"id":"nvidia/Nemotron-Next","likes":3,"tags":["transformers","license:other"],"pipeline_tag":"text-generation",
          "createdAt":"2026-10-23T18:49:27.000Z"}]""";

    final FixtureServer server = new FixtureServer();

    @AfterEach
    void stop() {
        server.close();
    }

    @Test
    void asksEachOrganisationForItsNewestModelsAndTagsTheProvider() {
        server.route("/api/models", FixtureServer.Reply.ok(NEWEST));
        HuggingFaceProviderOrgsCollector collector = new HuggingFaceProviderOrgsCollector(
            new SourceSettings(true, server.baseUrl(), "", Duration.ofSeconds(2), Duration.ZERO),
            ObservedHttp.restClientBuilder(Duration.ofSeconds(2), io.micrometer.observation.ObservationRegistry.NOOP),
            SourceFixtures.CLOCK, Resilience.NONE, new RunInterval(Duration.ofDays(1)), List.of("nvidia", "Qwen"), 20);

        CollectedBatch batch = collector.collect(null, null, 500);

        assertThat(server.requests).hasSize(2);
        assertThat(server.requests.getFirst()).contains("author=nvidia").contains("sort=createdAt").contains("direction=-1").contains("limit=20");
        assertThat(server.requests.get(1)).contains("author=Qwen");
        SourceHit hit = batch.getHits().getFirst();
        assertThat(hit.getSourceId()).isEqualTo("hf-provider-orgs");
        assertThat(hit.getTags()).contains("nvidia", "transformers");
        assertThat(batch.getCheckpoint()).isEqualTo(SourceFixtures.CLOCK.instant().toString());
    }
}
