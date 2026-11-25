package ai.searchavo.sources.tavily;

import static org.assertj.core.api.Assertions.assertThat;

import ai.searchavo.search.SourceHit;
import ai.searchavo.search.SourceQuery;
import ai.searchavo.shared.Audience;
import ai.searchavo.shared.CanonicalUrl;
import ai.searchavo.shared.DateFact;
import ai.searchavo.shared.DatePrecision;
import ai.searchavo.shared.ResourceType;
import ai.searchavo.shared.ValueOrigin;
import ai.searchavo.sources.SourceFixtures;
import ai.searchavo.sources.support.SourceSettings;
import ai.searchavo.testing.FixtureServer;
import ai.searchavo.testing.TestHttp;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class TavilySourceTest {

  static final String NEWS =
      """
      {"results":[
        {"url":"https://news.example/acme-launches-agent","title":"Acme launches an open-source AI agent framework",
         "content":"Acme released Forge, an open-source agent framework for building LLM agents.","published_date":"2026-10-21T08:00:00Z"},
        {"url":"https://news.example/football","title":"Local team wins the cup","content":"A football match report."}
      ]}\
      """;

  final FixtureServer server = new FixtureServer();

  @AfterEach
  void stop() {
    server.close();
  }

  TavilySource source() {
    return new TavilySource(
        new SourceSettings(
            true, server.baseUrl(), "test-token", Duration.ofSeconds(2), Duration.ZERO, null),
        TestHttp.restClientBuilder(
            Duration.ofSeconds(2), io.micrometer.observation.ObservationRegistry.NOOP),
        SourceFixtures.CLOCK,
        SourceFixtures.SHADOW_LIBRARIES);
  }

  @Test
  void plannedWebQueryIsSentAsWrittenWithoutKeywordsOrSuffix() {
    server.route("/search", FixtureServer.Reply.ok(NEWS));
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
    assertThat(hits.getFirst())
        .isEqualTo(
            new SourceHit(
                "live-web",
                "https://github.com/crystaldba/postgres-mcp",
                URI.create("https://github.com/crystaldba/postgres-mcp"),
                CanonicalUrl.of("https://github.com/crystaldba/postgres-mcp"),
                null,
                "crystaldba/postgres-mcp",
                "Postgres MCP Pro provides configurable read/write access and performance analysis for you and your AI agents.",
                ValueOrigin.SOURCE_PROVIDED,
                ResourceType.NEWS,
                Audience.BOTH,
                null,
                Set.of(),
                Set.of(),
                null,
                null,
                Set.of(),
                null,
                DateFact.unknown(),
                DateFact.unknown(),
                List.of(),
                SourceFixtures.CLOCK.instant()));

    assertThat(hits)
        .extracting(h -> h.getTitle())
        .containsExactly(
            "crystaldba/postgres-mcp",
            "Best Open Source Vector Databases for Production RAG 2026",
            "PDF Free | Chat with PDF AI - No ...",
            "Ollama - Get up and running with large language models");
    assertThat(hits.getFirst().getDescription())
        .isEqualTo(
            "Postgres MCP Pro provides configurable read/write access and performance analysis for you and your AI agents.");
    assertThat(hits.get(1).getDescription()).isEqualTo("A ranking of vector databases for RAG.");
    assertThat(hits.getFirst().getExternalId())
        .isEqualTo("https://github.com/crystaldba/postgres-mcp");
  }

  @Test
  void aNewsResultWhoseTitleNamesAnEventIsAnEvent() {
    JsonNode result =
        JsonMapper.builder()
            .build()
            .readTree(
                """
                {"url":"https://news.example.com/ai-summit-2026","title":"The AI Summit 2026 opens in Paris",
                 "content":"Speakers talk about LLM agents."}\
                """);

    var hit =
        TavilySource.toHit(
            result, "live-web", SourceFixtures.CLOCK.instant(), 0, SourceFixtures.SHADOW_LIBRARIES);

    assertThat(hit.getType()).isEqualTo(ResourceType.EVENT);
  }

  @Test
  void aCopyOfABookOnAShadowLibraryIsNeverAResult() {
    JsonNode result =
        JsonMapper.builder()
            .build()
            .readTree(
                """
                {"url":"https://ka.z-lib.fm/book/123/a-simple-guide-to-rag",
                 "title":"A Simple Guide to Retrieval Augmented Generation | download on Z-Library",
                 "content":"A guide to retrieval augmented generation and large language models."}\
                """);

    assertThat(
            TavilySource.toHit(
                result,
                "live-web",
                SourceFixtures.CLOCK.instant(),
                0,
                SourceFixtures.SHADOW_LIBRARIES))
        .isNull();
  }

  @Test
  void aPageOnTavilysOwnSiteIsNotAResultItIsNoPublishersArticle() {
    JsonNode result =
        JsonMapper.builder()
            .build()
            .readTree(
                """
                {"url":"https://tavily.com/knowledge/company/RDbdtfJgpC3W","title":"rag",
                 "content":"# RAG Aktiengesellschaft ID: RDbdtfJgpC3W Website: www.rag.de"}\
                """);

    assertThat(
            TavilySource.toHit(
                result,
                "live-web",
                SourceFixtures.CLOCK.instant(),
                0,
                SourceFixtures.SHADOW_LIBRARIES))
        .isNull();
    assertThat(
            TavilySource.toHit(
                result.deepCopy(),
                "live-web",
                SourceFixtures.CLOCK.instant(),
                0,
                ResourceType.ARTICLE,
                Set.of(),
                SourceFixtures.SHADOW_LIBRARIES))
        .isNull();
  }

  @Test
  void aPublisherWhoseHostOnlyContainsTavilyIsKept() {
    JsonNode result =
        JsonMapper.builder()
            .build()
            .readTree(
                """
                {"url":"https://nottavily.com/blog/llm-agents","title":"How LLM agents search the web",
                 "content":"An article about AI agents and LLM web search."}\
                """);

    assertThat(
            TavilySource.toHit(
                result,
                "live-web",
                SourceFixtures.CLOCK.instant(),
                0,
                SourceFixtures.SHADOW_LIBRARIES))
        .isNotNull();
  }

  @Test
  void aResultStatesItsDayItsPositionAndTheTagsTheCallerGives() {
    JsonNode result =
        JsonMapper.builder()
            .build()
            .readTree(
                """
                {"url":"https://www.lists.example.com/blog/llm-tools?utm_source=x","title":"10 Best LLM Tools",
                 "content":"LLM assistants for writing code.","published_date":"2026-10-01T08:00:00Z"}\
                """);

    var hit =
        TavilySource.toHit(
            result,
            "discovery-coding",
            SourceFixtures.CLOCK.instant(),
            4,
            ResourceType.ARTICLE,
            Set.of("list"),
            SourceFixtures.SHADOW_LIBRARIES);

    assertThat(hit)
        .isEqualTo(
            new SourceHit(
                "discovery-coding",
                "https://lists.example.com/blog/llm-tools",
                URI.create("https://www.lists.example.com/blog/llm-tools?utm_source=x"),
                CanonicalUrl.of("https://lists.example.com/blog/llm-tools"),
                null,
                "10 Best LLM Tools",
                "LLM assistants for writing code.",
                ValueOrigin.SOURCE_PROVIDED,
                ResourceType.ARTICLE,
                Audience.BOTH,
                null,
                Set.of(),
                Set.of("list"),
                null,
                null,
                Set.of(),
                null,
                new DateFact(
                    Instant.parse("2026-10-01T08:00:00Z"),
                    DatePrecision.INSTANT,
                    "discovery-coding",
                    List.of(),
                    false),
                DateFact.unknown(),
                List.of(),
                SourceFixtures.CLOCK.instant(),
                4));
  }

  @Test
  void aTypeTheCallerGivesIsKeptWhateverTheTitleSays() {
    JsonNode result =
        JsonMapper.builder()
            .build()
            .readTree(
                """
                {"url":"https://lists.example.com/blog/ai-event-tools","title":"10 Best AI Tools for Event Planning",
                 "content":"LLM assistants for planning a conference."}\
                """);

    var hit =
        TavilySource.toHit(
            result,
            "discovery-coding",
            SourceFixtures.CLOCK.instant(),
            0,
            ResourceType.ARTICLE,
            Set.of(),
            SourceFixtures.SHADOW_LIBRARIES);

    assertThat(hit.getType()).isEqualTo(ResourceType.ARTICLE);
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
                    Duration.ofMinutes(30),
                    null),
                TestHttp.restClientBuilder(
                    Duration.ofSeconds(2), io.micrometer.observation.ObservationRegistry.NOOP),
                SourceFixtures.CLOCK,
                SourceFixtures.SHADOW_LIBRARIES)
            .getDescriptor();

    assertThat(descriptor.getMetered()).isTrue();
    assertThat(descriptor.getCacheTtl()).isZero();
  }
}
