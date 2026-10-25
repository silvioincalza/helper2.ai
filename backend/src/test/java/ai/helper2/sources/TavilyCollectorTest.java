package ai.helper2.sources;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.platform.http.ObservedHttp;
import ai.helper2.scout.CollectedBatch;
import ai.helper2.search.SourceHit;
import ai.helper2.shared.ResourceType;
import ai.helper2.testing.FixtureServer;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TavilyCollectorTest {

    static final String RESULTS = """
        {"results":[
          {"url":"https://news.example/acme-launches-agent","title":"Acme launches an open-source AI agent framework",
           "content":"Acme released Forge, an open-source agent framework for building LLM agents.","published_date":"2026-10-21T08:00:00Z"},
          {"url":"https://news.example/football","title":"Local team wins the cup","content":"A football match report."}
        ]}""";

    final FixtureServer server = new FixtureServer();

    @AfterEach
    void stop() {
        server.close();
    }

    TavilyCollector collector(String token, List<String> queries) {
        return new TavilyCollector(new SourceSettings(true, server.baseUrl(), token, Duration.ofSeconds(2), Duration.ZERO),
            ObservedHttp.restClientBuilder(Duration.ofSeconds(2), io.micrometer.observation.ObservationRegistry.NOOP), GitHubSourceTest.CLOCK, new RunInterval(Duration.ofDays(5)), queries);
    }

    @Test
    void firstRunAsksForTheLastWeekOncePerQueryAndKeepsOnlyAiResultsDeduplicated() {
        server.route("/search", FixtureServer.Reply.ok(RESULTS));
        CollectedBatch batch = collector("tvly-test", List.of("new AI tool launched", "new MCP server released")).collect(null, null, 500);

        assertThat(server.requestBodies).hasSize(2).allSatisfy(body -> assertThat(body)
            .contains("\"topic\":\"news\"").contains("\"search_depth\":\"basic\"").contains("\"time_range\":\"week\"")
            .doesNotContain("start_date"));
        assertThat(batch.getHits()).extracting(SourceHit::getExternalId).containsExactly("https://news.example/acme-launches-agent");
        SourceHit hit = batch.getHits().getFirst();
        assertThat(hit.getSourceId()).isEqualTo("tavily-scout");
        assertThat(hit.getType()).isEqualTo(ResourceType.NEWS);
        assertThat(hit.getDescription()).contains("Forge");
        assertThat(batch.getCheckpoint()).isEqualTo(GitHubSourceTest.CLOCK.instant().toString());
    }

    @Test
    void laterRunsAskOnlyForResultsPublishedSinceThePreviousPass() {
        server.route("/search", FixtureServer.Reply.ok("{\"results\":[]}"));
        collector("tvly-test", List.of("new AI tool launched")).collect("2026-10-15T09:00:00Z", null, 500);
        assertThat(server.requestBodies.getFirst()).contains("\"start_date\":\"2026-10-15\"").doesNotContain("time_range");
    }

    @Test
    void spendsNoCreditsBeforeTheFiveDayIntervalHasPassed() {
        CollectedBatch batch = collector("tvly-test", List.of("new AI tool launched")).collect("2026-10-20T09:00:00Z", null, 500);
        assertThat(batch.getNotDue()).isTrue();
        assertThat(server.requests).isEmpty();
    }

    @Test
    void isDisabledWithoutAnApiKeyOrQueries() {
        assertThat(collector("", List.of("q")).getDescriptor().getEnabled()).isFalse();
        assertThat(collector("tvly-test", List.of()).getDescriptor().getEnabled()).isFalse();
    }
}
