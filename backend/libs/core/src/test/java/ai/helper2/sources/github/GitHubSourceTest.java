package ai.helper2.sources.github;

import static ai.helper2.sources.SourceFixtures.CLOCK;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import ai.helper2.search.PopularityKind;
import ai.helper2.search.SourceFailure;
import ai.helper2.search.SourceHit;
import ai.helper2.search.SourceQuery;
import ai.helper2.shared.DateStatus;
import ai.helper2.shared.ResourceType;
import ai.helper2.sources.support.SourceSettings;
import ai.helper2.testing.FixtureServer;
import ai.helper2.testing.TestHttp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class GitHubSourceTest {

  final FixtureServer server = new FixtureServer();

  @AfterEach
  void stop() {
    server.close();
  }

  GitHubSource source(String token) {
    return new GitHubSource(
        new SourceSettings(
            true, server.baseUrl(), token, Duration.ofSeconds(2), Duration.ofMinutes(30)),
        TestHttp.restClientBuilder(
            Duration.ofSeconds(2), io.micrometer.observation.ObservationRegistry.NOOP),
        CLOCK);
  }

  SourceQuery query(String text, List<String> expanded) {
    return new SourceQuery(text, expanded, null, 10);
  }

  @Test
  void mapsRealResponseShape() {
    server.route(
        "/search/repositories",
        FixtureServer.Reply.ok(FixtureServer.fixture("github-search.json")));
    List<SourceHit> hits = source("").search(query("mcp postgres", List.of()));
    assertThat(hits).isNotEmpty();
    SourceHit first = hits.getFirst();
    assertThat(first.getSourceId()).isEqualTo("github");
    assertThat(first.getCanonicalUrl().getValue())
        .isEqualTo("https://github.com/crystaldba/postgres-mcp");
    assertThat(first.getLicense()).isEqualTo("MIT");
    assertThat(first.getOpenSource()).isTrue();
    assertThat(first.getType()).isEqualTo(ResourceType.MCP_SERVER);
    assertThat(first.getPublished().getValue()).isEqualTo(Instant.parse("2025-03-24T18:28:53Z"));
    assertThat(first.getUpdated().getValue()).isEqualTo(Instant.parse("2026-08-17T05:06:35Z"));
    assertThat(first.getPopularity())
        .singleElement()
        .satisfies(p -> {
          assertThat(p.getKind()).isEqualTo(PopularityKind.GITHUB_STARS);
          assertThat(p.getValue()).isEqualTo(3420);
        });
    assertThat(first.getRank()).isZero();
  }

  @Test
  void aRepositoryStatesIfItIsAForkOrArchived() {
    server.route(
        "/search/repositories",
        FixtureServer.Reply.ok(
            """
            {"items":[
              {"full_name":"acme/llm-agent","html_url":"https://github.com/acme/llm-agent",
               "description":"LLM agent framework","fork":true,"archived":false},
              {"full_name":"acme/llm-tools","html_url":"https://github.com/acme/llm-tools",
               "description":"LLM tools","fork":false,"archived":true}]}\
            """));

    List<SourceHit> hits = source("").search(query("llm", List.of()));

    assertThat(hits)
        .extracting(h -> h.getVitality().getFork(), h -> h.getVitality().getArchived())
        .containsExactly(tuple(true, false), tuple(false, true));
  }

  @Test
  void sendsTokenOnlyWhenConfiguredAndNeverInTheUrl() {
    server.route("/search/repositories", FixtureServer.Reply.ok("{\"items\":[]}"));
    source("ghp_secret").search(query("x", List.of()));
    assertThat(server.requests.getFirst()).doesNotContain("ghp_secret");
    assertThat(server.requestHeaders.getFirst().get("Authorization"))
        .containsExactly("Bearer ghp_secret");
    source("").search(query("x", List.of()));
    assertThat(server.requestHeaders.get(1)).doesNotContainKey("Authorization");
  }

  private String sentQuery(SourceQuery query) {
    server.route("/search/repositories", FixtureServer.Reply.ok("{\"items\":[]}"));
    source("").search(query);
    String request =
        java.net.URLDecoder.decode(
            server.requests.getFirst(), java.nio.charset.StandardCharsets.UTF_8);
    return request.substring(request.indexOf("q=") + 2).replaceAll("&.*", "");
  }

  @Test
  void asksForForksTooLeavesOutArchivedAndAddsAlternativesToAOneWordQuery() {
    assertThat(sentQuery(query("rag", List.of("retrieval augmented generation", "vector search"))))
        .isEqualTo(
            "rag OR \"retrieval augmented generation\" OR \"vector search\" fork:true archived:false");
  }

  @Test
  void aQueryOfSeveralWordsIsSentAloneSoOrDoesNotBindToItsLastWord() {
    assertThat(sentQuery(query("vector database", List.of("embeddings", "similarity search"))))
        .isEqualTo("vector database fork:true archived:false");
  }

  @Test
  void aTranslatedQueryIsSentInEnglishOnly() {
    SourceQuery translated =
        new SourceQuery(
            "app per tradurre testi",
            List.of("app to translate texts", "translation"),
            null,
            10,
            List.of("app to translate texts", "app per tradurre testi"));

    assertThat(sentQuery(translated)).isEqualTo("app to translate texts fork:true archived:false");
  }

  @Test
  void searchQualifiersAndOperatorsInTheTextAreSentAsPlainWords() {
    assertThat(sentQuery(query("user:models -is:private \"x\"", List.of())))
        .isEqualTo("user models is private x fork:true archived:false");
  }

  @Test
  void aLongQueryIsCutAtAWordToStayInsideGitHubsLimit() {
    String words = "agent ".repeat(60).trim();

    String sent = sentQuery(query(words, List.of("x")));

    assertThat(sent.replace(" fork:true archived:false", ""))
        .hasSizeLessThanOrEqualTo(GitHubSource.MAX_WORDS_LENGTH)
        .endsWith("agent")
        .doesNotContain(" OR ");
  }

  @Test
  void rateLimitBecomesRateLimitedWithRetryAfterFromResetHeader() {
    long reset = Instant.parse("2026-10-22T10:00:45Z").getEpochSecond();
    server.route(
        "/search/repositories",
        new FixtureServer.Reply(
            403,
            "{\"message\":\"API rate limit exceeded\"}",
            Map.of("x-ratelimit-remaining", "0", "x-ratelimit-reset", Long.toString(reset)),
            0));
    assertThatThrownBy(() -> source("").search(query("x", List.of())))
        .isInstanceOfSatisfying(
            SourceFailure.RateLimited.class,
            e -> assertThat(e.getRetryAfter()).isEqualTo(Duration.ofSeconds(45)));
  }

  @Test
  void serverErrorBecomesUnavailable() {
    server.route("/search/repositories", new FixtureServer.Reply(502, "bad gateway", Map.of(), 0));
    assertThatThrownBy(() -> source("").search(query("x", List.of())))
        .isInstanceOf(SourceFailure.Unavailable.class);
  }

  @Test
  void malformedJsonBecomesUnavailable() {
    server.route("/search/repositories", FixtureServer.Reply.ok("<html>"));
    assertThatThrownBy(() -> source("").search(query("x", List.of())))
        .isInstanceOf(SourceFailure.Unavailable.class);
  }

  @Test
  void nonAiRepositoriesAreFilteredOutByTheRelevanceGate() {
    server.route(
        "/search/repositories",
        FixtureServer.Reply.ok(
            """
            {"items":[
              {"full_name":"a/csv-tool","html_url":"https://github.com/a/csv-tool","description":"Parse CSV files","topics":[],"stargazers_count":1},
              {"full_name":"a/llm-csv","html_url":"https://github.com/a/llm-csv","description":"Ask an LLM about CSV files","topics":[],"stargazers_count":1}
            ]}\
            """));
    assertThat(source("").search(query("csv", List.of())))
        .extracting(SourceHit::getTitle)
        .containsExactly("a/llm-csv");
  }

  @Test
  void missingDatesStayUnknown() {
    server.route(
        "/search/repositories",
        FixtureServer.Reply.ok(
            """
            {"items":[{"full_name":"a/agent","html_url":"https://github.com/a/agent","description":"AI agent","topics":["ai-agent"]}]}\
            """));
    SourceHit h = source("").search(query("agent", List.of())).getFirst();
    assertThat(h.getPublished().getStatus()).isEqualTo(DateStatus.UNKNOWN);
    assertThat(h.getLicense()).isNull();
    assertThat(h.getOpenSource()).as("no license means unknown, not open source").isNull();
    assertThat(h.getType()).isEqualTo(ResourceType.AGENT);
  }

  @Test
  void timeoutIsEnforcedByTheHttpClient() {
    server.route(
        "/search/repositories", new FixtureServer.Reply(200, "{\"items\":[]}", Map.of(), 3_000));
    GitHubSource fast =
        new GitHubSource(
            new SourceSettings(
                true, server.baseUrl(), "", Duration.ofMillis(200), Duration.ofMinutes(1)),
            TestHttp.restClientBuilder(
                Duration.ofMillis(200), io.micrometer.observation.ObservationRegistry.NOOP),
            CLOCK);
    assertThatThrownBy(() -> fast.search(query("x", List.of())))
        .isInstanceOf(SourceFailure.Unavailable.class);
  }

  @Test
  void searchesTheContentWordsWhenTheQueryHasThem() {
    server.route("/search/repositories", FixtureServer.Reply.ok("{\"items\":[]}"));
    source("")
        .search(
            new SourceQuery(
                "Python library to build retrieval-augmented generation over PDF files",
                List.of(),
                null,
                10,
                List.of("x"),
                List.of(),
                null,
                null,
                null,
                false,
                java.util.Set.of(),
                List.of("python", "retrieval-augmented", "generation", "pdf")));
    String q =
        java.net.URLDecoder.decode(
            server.requests.getFirst(), java.nio.charset.StandardCharsets.UTF_8);
    assertThat(q)
        .contains("q=python retrieval-augmented generation pdf fork:true")
        .doesNotContain("library")
        .doesNotContain(" OR ");
  }
}
