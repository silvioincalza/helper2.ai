package ai.helper2.sources.npm;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.search.PopularityKind;
import ai.helper2.search.SourceHit;
import ai.helper2.search.SourceQuery;
import ai.helper2.shared.DateStatus;
import ai.helper2.shared.ResourceType;
import ai.helper2.sources.SourceFixtures;
import ai.helper2.sources.support.SourceSettings;
import ai.helper2.testing.FixtureServer;
import ai.helper2.testing.TestHttp;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.util.UriComponentsBuilder;

class NpmSourceTest {

  final FixtureServer server = new FixtureServer();

  @AfterEach
  void stop() {
    server.close();
  }

  SourceSettings settings() {
    return new SourceSettings(
        true, server.baseUrl(), "", Duration.ofSeconds(2), Duration.ofMinutes(30));
  }

  SourceQuery q(String text) {
    return new SourceQuery(text, List.of(), null, 10);
  }

  @Test
  void npmMapsPackageRepositoryAndWeeklyDownloads() {
    server.route("/-/v1/search", FixtureServer.Reply.ok(FixtureServer.fixture("npm-search.json")));
    NpmSource src =
        new NpmSource(
            settings(),
            TestHttp.restClientBuilder(
                Duration.ofSeconds(2), io.micrometer.observation.ObservationRegistry.NOOP),
            SourceFixtures.CLOCK);
    List<SourceHit> hits = src.search(q("mcp postgres"));
    SourceHit h = hits.getFirst();
    assertThat(h.getUrl().toString()).isEqualTo("https://www.npmjs.com/package/dsh-mcp-postgres");
    assertThat(h.getType()).isEqualTo(ResourceType.MCP_SERVER);
    assertThat(h.getUpdated().getValue())
        .as("npm 'date' is the latest publish")
        .isEqualTo(Instant.parse("2026-10-07T13:19:44.227Z"));
    assertThat(h.getPublished().getStatus())
        .as("search API has no creation date")
        .isEqualTo(DateStatus.UNKNOWN);
    assertThat(h.getPopularity())
        .singleElement()
        .satisfies(p -> assertThat(p.getKind()).isEqualTo(PopularityKind.NPM_DOWNLOADS_WEEKLY));
  }

  @Test
  void npmGetsAtMost64CharactersCutAtAWordBecauseItRejectsLongerText() {
    server.route("/-/v1/search", FixtureServer.Reply.ok("{\"objects\":[]}"));
    NpmSource src = source();
    src.search(
        new SourceQuery(
            "an open-source MCP server so my agent can query a Postgres database",
            List.of("model context protocol"),
            null,
            10));
    assertThat(sentText())
        .as("npm answers 400 ERR_TEXT_LENGTH above 64 characters")
        .isEqualTo("an open-source MCP server so my agent can query a Postgres");
  }

  @Test
  void npmCutsASingleWordLongerThan64Characters() {
    server.route("/-/v1/search", FixtureServer.Reply.ok("{\"objects\":[]}"));
    source().search(q("x".repeat(80)));
    assertThat(sentText()).isEqualTo("x".repeat(64));
  }

  @Test
  void npmGetsAShortQueryWithItsExpandedTermsUnchanged() {
    server.route("/-/v1/search", FixtureServer.Reply.ok("{\"objects\":[]}"));
    source().search(new SourceQuery("mcp postgres", List.of("database"), null, 10));
    assertThat(sentText()).isEqualTo("mcp postgres database");
  }

  NpmSource source() {
    return new NpmSource(
        settings(),
        TestHttp.restClientBuilder(
            Duration.ofSeconds(2), io.micrometer.observation.ObservationRegistry.NOOP),
        SourceFixtures.CLOCK);
  }

  String sentText() {
    return UriComponentsBuilder.fromUriString(server.requests.getFirst())
        .build()
        .getQueryParams()
        .getFirst("text")
        .transform(t -> URLDecoder.decode(t, StandardCharsets.UTF_8));
  }

  @Test
  void npmPackageWithAnUnusableLinkFallsBackToItsRegistryPage() {
    server.route(
        "/-/v1/search",
        FixtureServer.Reply.ok(
            """
            {"objects":[{"package":{"name":"llm-kit","description":"LLM toolkit",
              "links":{"npm":"npm:llm-kit","repository":"git+https://github.com/acme/tools.git"}}}]}\
            """));
    NpmSource src =
        new NpmSource(
            settings(),
            TestHttp.restClientBuilder(
                Duration.ofSeconds(2), io.micrometer.observation.ObservationRegistry.NOOP),
            SourceFixtures.CLOCK);
    SourceHit h = src.search(q("llm")).getFirst();
    assertThat(h.getUrl().toString()).isEqualTo("https://www.npmjs.com/package/llm-kit");
    assertThat(h.getCanonicalUrl().getValue()).isEqualTo("https://npmjs.com/package/llm-kit");
    assertThat(h.getRepositoryUrl().getValue()).isEqualTo("https://github.com/acme/tools");
  }

  @Test
  void npmRepositoryLinkKeepsItsMonorepoSubDirectory() {
    server.route(
        "/-/v1/search",
        FixtureServer.Reply.ok(
            """
            {"objects":[{"package":{"name":"@acme/agent-player","description":"Agent player",
              "links":{"npm":"https://www.npmjs.com/package/@acme/agent-player",
                       "repository":"https://github.com/acme/mono/tree/main/packages/player"}}}]}\
            """));
    NpmSource src =
        new NpmSource(
            settings(),
            TestHttp.restClientBuilder(
                Duration.ofSeconds(2), io.micrometer.observation.ObservationRegistry.NOOP),
            SourceFixtures.CLOCK);
    assertThat(src.search(q("agent")).getFirst().getRepositoryUrl().getValue())
        .isEqualTo("https://github.com/acme/mono/tree/HEAD/packages/player");
  }
}
