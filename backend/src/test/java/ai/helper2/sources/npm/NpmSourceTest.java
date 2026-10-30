package ai.helper2.sources.npm;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.platform.http.ObservedHttp;
import ai.helper2.search.PopularityKind;
import ai.helper2.search.SourceHit;
import ai.helper2.search.SourceQuery;
import ai.helper2.shared.DateStatus;
import ai.helper2.shared.ResourceType;
import ai.helper2.sources.SourceFixtures;
import ai.helper2.sources.support.SourceSettings;
import ai.helper2.testing.FixtureServer;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class NpmSourceTest {

    final FixtureServer server = new FixtureServer();

    @AfterEach
    void stop() {
        server.close();
    }

    SourceSettings settings() {
        return new SourceSettings(true, server.baseUrl(), "", Duration.ofSeconds(2), Duration.ofMinutes(30));
    }

    SourceQuery q(String text) {
        return new SourceQuery(text, List.of(), null, 10);
    }

    @Test
    void npmMapsPackageRepositoryAndWeeklyDownloads() {
        server.route("/-/v1/search", FixtureServer.Reply.ok(FixtureServer.fixture("npm-search.json")));
        NpmSource src = new NpmSource(settings(), ObservedHttp.restClientBuilder(Duration.ofSeconds(2), io.micrometer.observation.ObservationRegistry.NOOP), SourceFixtures.CLOCK);
        List<SourceHit> hits = src.search(q("mcp postgres"));
        SourceHit h = hits.getFirst();
        assertThat(h.getUrl().toString()).isEqualTo("https://www.npmjs.com/package/dsh-mcp-postgres");
        assertThat(h.getType()).isEqualTo(ResourceType.MCP_SERVER);
        assertThat(h.getUpdated().getValue()).as("npm 'date' is the latest publish").isEqualTo(Instant.parse("2026-10-07T13:19:44.227Z"));
        assertThat(h.getPublished().getStatus()).as("search API has no creation date").isEqualTo(DateStatus.UNKNOWN);
        assertThat(h.getPopularity()).singleElement().satisfies(p -> assertThat(p.getKind()).isEqualTo(PopularityKind.NPM_DOWNLOADS_WEEKLY));
    }

    @Test
    void npmPackageWithAnUnusableLinkFallsBackToItsRegistryPage() {
        server.route("/-/v1/search", FixtureServer.Reply.ok("""
            {"objects":[{"package":{"name":"llm-kit","description":"LLM toolkit",
              "links":{"npm":"npm:llm-kit","repository":"git+https://github.com/acme/tools.git"}}}]}"""));
        NpmSource src = new NpmSource(settings(), ObservedHttp.restClientBuilder(Duration.ofSeconds(2), io.micrometer.observation.ObservationRegistry.NOOP), SourceFixtures.CLOCK);
        SourceHit h = src.search(q("llm")).getFirst();
        assertThat(h.getUrl().toString()).isEqualTo("https://www.npmjs.com/package/llm-kit");
        assertThat(h.getCanonicalUrl().getValue()).isEqualTo("https://npmjs.com/package/llm-kit");
        assertThat(h.getRepositoryUrl().getValue()).isEqualTo("https://github.com/acme/tools");
    }

    @Test
    void npmRepositoryLinkKeepsItsMonorepoSubDirectory() {
        server.route("/-/v1/search", FixtureServer.Reply.ok("""
            {"objects":[{"package":{"name":"@acme/agent-player","description":"Agent player",
              "links":{"npm":"https://www.npmjs.com/package/@acme/agent-player",
                       "repository":"https://github.com/acme/mono/tree/main/packages/player"}}}]}"""));
        NpmSource src = new NpmSource(settings(), ObservedHttp.restClientBuilder(Duration.ofSeconds(2), io.micrometer.observation.ObservationRegistry.NOOP), SourceFixtures.CLOCK);
        assertThat(src.search(q("agent")).getFirst().getRepositoryUrl().getValue())
            .isEqualTo("https://github.com/acme/mono/tree/main/packages/player");
    }
}
