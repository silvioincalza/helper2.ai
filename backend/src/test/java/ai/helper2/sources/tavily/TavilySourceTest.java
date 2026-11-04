package ai.helper2.sources.tavily;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.platform.http.ObservedHttp;
import ai.helper2.search.SourceQuery;
import ai.helper2.shared.WebPageTitle;
import ai.helper2.sources.SourceFixtures;
import ai.helper2.sources.support.SourceSettings;
import ai.helper2.testing.FixtureServer;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * What the live Tavily source sends: the guide's web query when planned, the catalog keywords
 * otherwise.
 */
class TavilySourceTest {

  final FixtureServer server = new FixtureServer();

  @AfterEach
  void stop() {
    server.close();
  }

  TavilySource source() {
    return new TavilySource(
        new SourceSettings(
            true, server.baseUrl(), "test-token", Duration.ofSeconds(2), Duration.ZERO),
        ObservedHttp.restClientBuilder(
            Duration.ofSeconds(2), io.micrometer.observation.ObservationRegistry.NOOP),
        SourceFixtures.CLOCK);
  }

  @Test
  void plannedWebQueryIsSentAsWrittenWithoutKeywordsOrSuffix() {
    server.route("/search", FixtureServer.Reply.ok(TavilyCollectorTest.RESULTS));
    SourceQuery query =
        new SourceQuery(
            "NVIDIA AI agents",
            List.of("agentic"),
            null,
            10,
            List.of("NVIDIA AI agents"),
            List.of(),
            null,
            "NVIDIA AI agents announcement this week");

    var hits = source().search(query);

    assertThat(server.requestBodies.getFirst())
        .contains("\"query\":\"NVIDIA AI agents announcement this week\"")
        .doesNotContain("agentic")
        .doesNotContain("artificial intelligence");
    assertThat(hits)
        .extracting(h -> h.getTitle())
        .containsExactly("Acme launches an open-source AI agent framework");
  }

  static final String PAGE_TITLES =
      """
      {"results":[
        {"url":"https://github.com/crystaldba/postgres-mcp","title":"GitHub - crystaldba/postgres-mcp: Postgres MCP Pro provides configurable read/write access and performance analysis for you and your AI agents. · GitHub"},
        {"url":"https://scored.tools/vector-databases","title":"Best Open Source Vector Databases for Production RAG 2026 | scored.tools",
         "content":"A ranking of vector databases for RAG."},
        {"url":"https://www.kaily.ai/chat-pdf","title":"PDF Free | Chat with PDF AI - No ... - Kaily.ai","content":"Chat with your PDF using AI."},
        {"url":"https://ollama.com/","title":"Ollama  -  Get up and running with large language models","content":"Run LLMs locally."}
      ]}\
      """;

  @Test
  void webPageTitlesLoseTheSiteNameAndGitHubPagesShowTheRepository() {
    server.route("/search", FixtureServer.Reply.ok(PAGE_TITLES));

    var hits = source().search(new SourceQuery("vector database", List.of(), null, 10));

    assertThat(hits)
        .extracting(h -> h.getTitle())
        .containsExactly(
            "crystaldba/postgres-mcp",
            "Best Open Source Vector Databases for Production RAG 2026",
            "PDF Free | Chat with PDF AI - No ...",
            "Ollama - Get up and running with large language models");
    // Tavily gave no content for the repository page: its GitHub description becomes the
    // description.
    assertThat(hits.getFirst().getDescription())
        .isEqualTo(
            "Postgres MCP Pro provides configurable read/write access and performance analysis for you and your AI agents.");
    assertThat(hits.get(1).getDescription()).isEqualTo("A ranking of vector databases for RAG.");
    // The identity stays the page URL.
    assertThat(hits.getFirst().getExternalId())
        .isEqualTo("https://github.com/crystaldba/postgres-mcp");
  }

  @Test
  void theTavilySourcesAreTheOnesWhoseTitlesAreCleanedOnRead() {
    assertThat(WebPageTitle.SOURCES)
        .containsExactlyInAnyOrder(
            source().getDescriptor().getId(), TavilyCollector.ID, CourseCollector.ID);
  }

  @Test
  void withoutAPlannedWebQueryTheKeywordsGetTheAiBias() {
    server.route("/search", FixtureServer.Reply.ok("{\"results\":[]}"));

    source().search(new SourceQuery("speech recognition", List.of("asr"), null, 10));

    assertThat(server.requestBodies.getFirst())
        .contains("\"query\":\"speech recognition asr artificial intelligence\"");
  }

  @Test
  void eachCallCostsACreditSoTheSourceIsMeteredAndKeepsNoPerSourceCache() {
    var descriptor =
        new TavilySource(
                new SourceSettings(
                    true,
                    server.baseUrl(),
                    "test-token",
                    Duration.ofSeconds(2),
                    Duration.ofMinutes(30)),
                ObservedHttp.restClientBuilder(
                    Duration.ofSeconds(2), io.micrometer.observation.ObservationRegistry.NOOP),
                SourceFixtures.CLOCK)
            .getDescriptor();

    assertThat(descriptor.getMetered()).isTrue();
    assertThat(descriptor.getCacheTtl()).isZero();
  }
}
