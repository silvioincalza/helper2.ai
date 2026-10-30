package ai.helper2.sources.tavily;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.platform.http.ObservedHttp;
import ai.helper2.search.SourceQuery;
import ai.helper2.sources.SourceFixtures;
import ai.helper2.sources.support.SourceSettings;
import ai.helper2.testing.FixtureServer;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** What the live Tavily source sends: the guide's web query when planned, the catalog keywords otherwise. */
class TavilySourceTest {

    final FixtureServer server = new FixtureServer();

    @AfterEach
    void stop() {
        server.close();
    }

    TavilySource source() {
        return new TavilySource(new SourceSettings(true, server.baseUrl(), "test-token", Duration.ofSeconds(2), Duration.ZERO),
            ObservedHttp.restClientBuilder(Duration.ofSeconds(2), io.micrometer.observation.ObservationRegistry.NOOP), SourceFixtures.CLOCK);
    }

    @Test
    void plannedWebQueryIsSentAsWrittenWithoutKeywordsOrSuffix() {
        server.route("/search", FixtureServer.Reply.ok(TavilyCollectorTest.RESULTS));
        SourceQuery query = new SourceQuery("NVIDIA AI agents", List.of("agentic"), null, 10, List.of("NVIDIA AI agents"),
            List.of(), null, "NVIDIA AI agents announcement this week");

        var hits = source().search(query);

        assertThat(server.requestBodies.getFirst())
            .contains("\"query\":\"NVIDIA AI agents announcement this week\"")
            .doesNotContain("agentic")
            .doesNotContain("artificial intelligence");
        assertThat(hits).extracting(h -> h.getTitle()).containsExactly("Acme launches an open-source AI agent framework");
    }

    @Test
    void withoutAPlannedWebQueryTheKeywordsGetTheAiBias() {
        server.route("/search", FixtureServer.Reply.ok("{\"results\":[]}"));

        source().search(new SourceQuery("speech recognition", List.of("asr"), null, 10));

        assertThat(server.requestBodies.getFirst()).contains("\"query\":\"speech recognition asr artificial intelligence\"");
    }

    @Test
    void eachCallCostsACreditSoTheSourceIsMeteredAndKeepsNoPerSourceCache() {
        var descriptor = new TavilySource(new SourceSettings(true, server.baseUrl(), "test-token", Duration.ofSeconds(2), Duration.ofMinutes(30)),
            ObservedHttp.restClientBuilder(Duration.ofSeconds(2), io.micrometer.observation.ObservationRegistry.NOOP), SourceFixtures.CLOCK)
            .getDescriptor();

        assertThat(descriptor.getMetered()).isTrue();
        assertThat(descriptor.getCacheTtl()).isZero();
    }
}
