package ai.helper2.sources.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import ai.helper2.platform.resilience.Resilience;
import ai.helper2.scout.CollectedBatch;
import ai.helper2.search.SourceHit;
import ai.helper2.shared.ResourceType;
import ai.helper2.sources.SourceFixtures;
import ai.helper2.sources.support.RunInterval;
import ai.helper2.sources.support.SourceSettings;
import ai.helper2.testing.FixtureServer;
import ai.helper2.testing.TestHttp;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class McpRegistryCollectorTest {

  final FixtureServer server = new FixtureServer();

  @AfterEach
  void stop() {
    server.close();
  }

  McpRegistryCollector collector() {
    return new McpRegistryCollector(
        new SourceSettings(true, server.baseUrl(), "", Duration.ofSeconds(2), Duration.ZERO),
        TestHttp.restClientBuilder(
            Duration.ofSeconds(2), io.micrometer.observation.ObservationRegistry.NOOP),
        SourceFixtures.CLOCK,
        Resilience.NONE);
  }

  @Test
  void collectsOnlyLatestVersionsAndReturnsNextCursor() {
    server.route(
        "/v0.1/servers",
        FixtureServer.Reply.ok(FixtureServer.fixture("mcp-servers-postgres.json")));
    CollectedBatch c = collector().collect(null, null, 20);
    assertThat(c.getHits())
        .allSatisfy(h -> {
          assertThat(h.getType()).isEqualTo(ResourceType.MCP_SERVER);
          assertThat(h.getSourceId()).isEqualTo("mcp-registry");
          assertThat(h.getPublished().getValue()).isNotNull();
        });
    assertThat(c.getHits()).extracting(SourceHit::getExternalId).doesNotHaveDuplicates();
    assertThat(c.getNextCursor()).isNotNull();
  }

  @Test
  void incrementalRunsAskOnlyForUpdatesSinceTheCheckpoint() {
    server.route(
        "/v0.1/servers", FixtureServer.Reply.ok("{\"servers\":[],\"metadata\":{\"count\":0}}"));
    CollectedBatch c = collector().collect("2026-10-20T00:00:00Z", null, 50);
    assertThat(server.requests.getFirst()).contains("updated_since=2026-10-20T00:00:00Z");
    assertThat(c.getHits()).isEmpty();
    assertThat(c.getCheckpoint()).isEqualTo(Instant.parse("2026-10-22T10:00:00Z").toString());
  }

  @Test
  void preferredLinkIsRepositoryThenWebsiteThenRegistryEntry() {
    server.route(
        "/v0.1/servers",
        FixtureServer.Reply.ok(
            """
            {"servers":[
              {"server":{"name":"io.github.a/x","description":"d","version":"1","repository":{"url":"https://github.com/a/x","source":"github"},"websiteUrl":"https://x.example"},
               "_meta":{"io.modelcontextprotocol.registry/official":{"isLatest":true,"publishedAt":"2026-01-01T00:00:00Z","updatedAt":"2026-02-01T00:00:00Z"}}},
              {"server":{"name":"remote.only/y","description":"d","version":"1","remotes":[{"type":"sse","url":"https://y.example/mcp"}]},
               "_meta":{"io.modelcontextprotocol.registry/official":{"isLatest":true,"publishedAt":"2026-01-01T00:00:00Z","updatedAt":"2026-02-01T00:00:00Z"}}}
            ],"metadata":{"count":2}}\
            """));
    var hits = collector().collect(null, null, 50).getHits();
    assertThat(hits.get(0).getCanonicalUrl().getValue()).isEqualTo("https://github.com/a/x");
    assertThat(hits.get(1).getUrl().toString())
        .startsWith(server.baseUrl() + "/v0.1/servers/remote.only%2Fy");
  }

  @Test
  void serversPublishedFromOneRepositoryKeepOneIdentityEach() {
    server.route(
        "/v0.1/servers",
        FixtureServer.Reply.ok(
            """
            {"servers":[
              {"server":{"name":"io.github.awslabs/a","description":"d","version":"1","repository":{"url":"https://github.com/awslabs/mcp","source":"github","subfolder":"src/a-server"}},
               "_meta":{"io.modelcontextprotocol.registry/official":{"isLatest":true,"publishedAt":"2026-01-01T00:00:00Z","updatedAt":"2026-02-01T00:00:00Z"}}},
              {"server":{"name":"io.github.awslabs/b","description":"d","version":"1","repository":{"url":"https://github.com/awslabs/mcp.git","source":"github","subfolder":"./src/b-server/"}},
               "_meta":{"io.modelcontextprotocol.registry/official":{"isLatest":true,"publishedAt":"2026-01-01T00:00:00Z","updatedAt":"2026-02-01T00:00:00Z"}}}
            ],"metadata":{"count":2}}\
            """));
    var hits = collector().collect(null, null, 50).getHits();
    assertThat(hits)
        .extracting(h -> h.getCanonicalUrl().getValue())
        .containsExactly(
            "https://github.com/awslabs/mcp/tree/HEAD/src/a-server",
            "https://github.com/awslabs/mcp/tree/HEAD/src/b-server");
    assertThat(hits)
        .extracting(h -> h.getRepositoryUrl().getValue())
        .containsOnly("https://github.com/awslabs/mcp");
  }

  @Test
  void aSubFolderThatLeavesTheRepositoryOrIsNotAPlainPathIsIgnored() {
    String repo = "https://github.com/victim/mcp";

    assertThat(McpRegistryCollector.serverFolder(repo, "src/../../attacker/evil")).isEqualTo(repo);
    assertThat(McpRegistryCollector.serverFolder(repo, "src/%2e%2e/x")).isEqualTo(repo);
    assertThat(McpRegistryCollector.serverFolder(repo, "a b")).isEqualTo(repo);
    assertThat(McpRegistryCollector.serverFolder(repo, "x/".repeat(200))).isEqualTo(repo);
    assertThat(McpRegistryCollector.serverFolder("https://evil.com/github.com/x/y", "src"))
        .isEqualTo("https://evil.com/github.com/x/y");
    assertThat(McpRegistryCollector.serverFolder("https://github.com/o/r/tree/main/x", "src"))
        .isEqualTo("https://github.com/o/r/tree/main/x");
    assertThat(
            McpRegistryCollector.serverFolder("https://github.com/O/R.git#readme", ".mcp/server"))
        .isEqualTo("https://github.com/o/r/tree/HEAD/.mcp/server");
  }

  @Test
  void aDeletedServerIsWithdrawnAndADeprecatedOneSaysSo() {
    server.route(
        "/v0.1/servers",
        FixtureServer.Reply.ok(
            """
            {"servers":[
              {"server":{"name":"io.github.acme/old","description":"d","version":"2","repository":{"url":"https://github.com/acme/old","source":"github"}},
               "_meta":{"io.modelcontextprotocol.registry/official":{"status":"deprecated","isLatest":true,"publishedAt":"2026-01-01T00:00:00Z","updatedAt":"2026-02-01T00:00:00Z"}}},
              {"server":{"name":"io.github.acme/gone","description":"d","version":"1","repository":{"url":"https://github.com/acme/gone","source":"github"}},
               "_meta":{"io.modelcontextprotocol.registry/official":{"status":"deleted","isLatest":true,"publishedAt":"2026-01-01T00:00:00Z","updatedAt":"2026-02-01T00:00:00Z"}}},
              {"server":{"name":"io.github.acme/live","description":"d","version":"1","repository":{"url":"https://github.com/acme/live","source":"github"}},
               "_meta":{"io.modelcontextprotocol.registry/official":{"status":"active","isLatest":true,"publishedAt":"2026-01-01T00:00:00Z","updatedAt":"2026-02-01T00:00:00Z"}}}
            ],"metadata":{"count":3}}\
            """));

    var batch = collector().collect(null, null, 50);

    assertThat(batch.getHits())
        .extracting(h -> h.getExternalId(), h -> h.getVitality().getDeprecated())
        .containsExactly(tuple("io.github.acme/old", true), tuple("io.github.acme/live", false));
    assertThat(batch.getWithdrawn()).containsExactly("io.github.acme/gone");
  }

  McpRegistryCollector hourly() {
    return new McpRegistryCollector(
        new SourceSettings(true, server.baseUrl(), "", Duration.ofSeconds(2), Duration.ZERO),
        TestHttp.restClientBuilder(
            Duration.ofSeconds(2), io.micrometer.observation.ObservationRegistry.NOOP),
        SourceFixtures.CLOCK,
        Resilience.NONE,
        new RunInterval(Duration.ofHours(1)));
  }

  @Test
  void newPassWaitsForTheIntervalSoTheRegistryIsPolledAboutHourly() {
    String tenMinutesAgo = SourceFixtures.CLOCK.instant().minus(Duration.ofMinutes(10)).toString();

    assertThat(hourly().collect(tenMinutesAgo, null, 50).getNotDue()).isTrue();
  }

  @Test
  void halfFinishedPassContinuesBeforeTheIntervalEnds() {
    server.route(
        "/v0.1/servers",
        FixtureServer.Reply.ok(FixtureServer.fixture("mcp-servers-postgres.json")));
    String tenMinutesAgo = SourceFixtures.CLOCK.instant().minus(Duration.ofMinutes(10)).toString();

    CollectedBatch c = hourly().collect(tenMinutesAgo, "next-page", 50);

    assertThat(c.getNotDue()).isFalse();
    assertThat(c.getHits()).isNotEmpty();
  }

  @Test
  void asksTheRegistryForLatestVersionsOnly() {
    server.route(
        "/v0.1/servers", FixtureServer.Reply.ok("{\"servers\":[],\"metadata\":{\"count\":0}}"));

    collector().collect(null, null, 50);

    assertThat(server.requests.getFirst()).contains("version=latest");
  }

  @Test
  void itsOwnItemBudgetReplacesTheRunsWhenLarger() {
    server.route(
        "/v0.1/servers",
        FixtureServer.Reply.ok(FixtureServer.fixture("mcp-servers-postgres.json")));
    McpRegistryCollector wide =
        new McpRegistryCollector(
            new SourceSettings(true, server.baseUrl(), "", Duration.ofSeconds(2), Duration.ZERO),
            TestHttp.restClientBuilder(
                Duration.ofSeconds(2), io.micrometer.observation.ObservationRegistry.NOOP),
            SourceFixtures.CLOCK,
            Resilience.NONE,
            new RunInterval(Duration.ZERO),
            5000);

    collector().collect(null, null, 1);
    int narrowCalls = server.requests.size();
    wide.collect(null, null, 1);
    int wideCalls = server.requests.size() - narrowCalls;

    assertThat(narrowCalls).as("the run's budget of 1 stops after one page").isEqualTo(1);
    assertThat(wideCalls).as("its own budget reads on").isGreaterThan(1);
  }
}
