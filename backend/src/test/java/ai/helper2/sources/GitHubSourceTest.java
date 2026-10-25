package ai.helper2.sources;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.helper2.platform.http.ObservedHttp;
import ai.helper2.search.PopularityKind;
import ai.helper2.search.SourceFailure;
import ai.helper2.search.SourceHit;
import ai.helper2.search.SourceQuery;
import ai.helper2.shared.DateStatus;
import ai.helper2.shared.ResourceType;
import ai.helper2.testing.FixtureServer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class GitHubSourceTest {

    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-22T10:00:00Z"), ZoneOffset.UTC);
    final FixtureServer server = new FixtureServer();

    @AfterEach
    void stop() {
        server.close();
    }

    GitHubSource source(String token) {
        return new GitHubSource(new SourceSettings(true, server.baseUrl(), token, Duration.ofSeconds(2), Duration.ofMinutes(30)),
            ObservedHttp.restClientBuilder(Duration.ofSeconds(2), io.micrometer.observation.ObservationRegistry.NOOP), CLOCK);
    }

    SourceQuery query(String text, List<String> expanded) {
        return new SourceQuery(text, expanded, null, 10);
    }

    @Test
    void mapsRealResponseShape() {
        server.route("/search/repositories", FixtureServer.Reply.ok(FixtureServer.fixture("github-search.json")));
        List<SourceHit> hits = source("").search(query("mcp postgres", List.of()));
        assertThat(hits).isNotEmpty();
        SourceHit first = hits.getFirst();
        assertThat(first.getSourceId()).isEqualTo("github");
        assertThat(first.getCanonicalUrl().getValue()).isEqualTo("https://github.com/crystaldba/postgres-mcp");
        assertThat(first.getLicense()).isEqualTo("MIT");
        assertThat(first.getOpenSource()).isTrue();
        assertThat(first.getType()).isEqualTo(ResourceType.MCP_SERVER);
        assertThat(first.getPublished().getValue()).isEqualTo(Instant.parse("2025-03-24T18:28:53Z"));
        // pushed_at, not updated_at: updated_at moves on every star and would overstate freshness.
        assertThat(first.getUpdated().getValue()).isEqualTo(Instant.parse("2026-08-17T05:06:35Z"));
        assertThat(first.getPopularity()).singleElement().satisfies(p -> {
            assertThat(p.getKind()).isEqualTo(PopularityKind.GITHUB_STARS);
            assertThat(p.getValue()).isEqualTo(3420);
        });
        assertThat(first.getRank()).isZero();
    }

    @Test
    void sendsTokenOnlyWhenConfiguredAndNeverInTheUrl() {
        server.route("/search/repositories", FixtureServer.Reply.ok("{\"items\":[]}"));
        source("ghp_secret").search(query("x", List.of()));
        assertThat(server.requests.getFirst()).doesNotContain("ghp_secret");
        assertThat(server.requestHeaders.getFirst().get("Authorization")).containsExactly("Bearer ghp_secret");
        source("").search(query("x", List.of()));
        assertThat(server.requestHeaders.get(1)).doesNotContainKey("Authorization");
    }

    @Test
    void excludesForksAndArchivedAndAddsExpandedTerms() {
        server.route("/search/repositories", FixtureServer.Reply.ok("{\"items\":[]}"));
        source("").search(query("app per tradurre", List.of("translation")));
        String q = java.net.URLDecoder.decode(server.requests.getFirst(), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(q).contains("fork:false").contains("archived:false").contains("OR translation");
    }

    @Test
    void rateLimitBecomesRateLimitedWithRetryAfterFromResetHeader() {
        long reset = Instant.parse("2026-10-22T10:00:45Z").getEpochSecond();
        server.route("/search/repositories", new FixtureServer.Reply(403, "{\"message\":\"API rate limit exceeded\"}",
            Map.of("x-ratelimit-remaining", "0", "x-ratelimit-reset", Long.toString(reset)), 0));
        assertThatThrownBy(() -> source("").search(query("x", List.of())))
            .isInstanceOfSatisfying(SourceFailure.RateLimited.class,
                e -> assertThat(e.getRetryAfter()).isEqualTo(Duration.ofSeconds(45)));
    }

    @Test
    void serverErrorBecomesUnavailable() {
        server.route("/search/repositories", new FixtureServer.Reply(502, "bad gateway", Map.of(), 0));
        assertThatThrownBy(() -> source("").search(query("x", List.of()))).isInstanceOf(SourceFailure.Unavailable.class);
    }

    @Test
    void malformedJsonBecomesUnavailable() {
        server.route("/search/repositories", FixtureServer.Reply.ok("<html>"));
        assertThatThrownBy(() -> source("").search(query("x", List.of()))).isInstanceOf(SourceFailure.Unavailable.class);
    }

    @Test
    void nonAiRepositoriesAreFilteredOutByTheRelevanceGate() {
        server.route("/search/repositories", FixtureServer.Reply.ok("""
            {"items":[
              {"full_name":"a/csv-tool","html_url":"https://github.com/a/csv-tool","description":"Parse CSV files","topics":[],"stargazers_count":1},
              {"full_name":"a/llm-csv","html_url":"https://github.com/a/llm-csv","description":"Ask an LLM about CSV files","topics":[],"stargazers_count":1}
            ]}"""));
        assertThat(source("").search(query("csv", List.of()))).extracting(SourceHit::getTitle).containsExactly("a/llm-csv");
    }

    @Test
    void missingDatesStayUnknown() {
        server.route("/search/repositories", FixtureServer.Reply.ok("""
            {"items":[{"full_name":"a/agent","html_url":"https://github.com/a/agent","description":"AI agent","topics":["ai-agent"]}]}"""));
        SourceHit h = source("").search(query("agent", List.of())).getFirst();
        assertThat(h.getPublished().getStatus()).isEqualTo(DateStatus.UNKNOWN);
        assertThat(h.getLicense()).isNull();
        assertThat(h.getOpenSource()).as("no license means unknown, not open source").isNull();
        assertThat(h.getType()).isEqualTo(ResourceType.AGENT);
    }

    @Test
    void timeoutIsEnforcedByTheHttpClient() {
        server.route("/search/repositories", new FixtureServer.Reply(200, "{\"items\":[]}", Map.of(), 3_000));
        GitHubSource fast = new GitHubSource(new SourceSettings(true, server.baseUrl(), "", Duration.ofMillis(200), Duration.ofMinutes(1)),
            ObservedHttp.restClientBuilder(Duration.ofMillis(200), io.micrometer.observation.ObservationRegistry.NOOP), CLOCK);
        assertThatThrownBy(() -> fast.search(query("x", List.of()))).isInstanceOf(SourceFailure.Unavailable.class);
    }
}
