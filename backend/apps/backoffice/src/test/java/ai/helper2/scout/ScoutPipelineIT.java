package ai.helper2.scout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.helper2.TestcontainersConfiguration;
import ai.helper2.catalog.ResourceDetail;
import ai.helper2.catalog.usecase.GetResourceDetail;
import ai.helper2.shared.CanonicalUrl;
import ai.helper2.shared.ResourceId;
import ai.helper2.shared.ValueOrigin;
import ai.helper2.shared.Verification;
import ai.helper2.testing.FixtureServer;
import ai.helper2.testing.SourcesOff;
import com.jayway.jsonpath.JsonPath;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SourcesOff
@SpringBootTest(
    properties = {
      "spring.ai.model.chat=fake",
      "helper2.llm.reasoning-model=fake-super",
      "logging.structured.format.console=",
      "helper2.scout.max-enrichments-per-run=1",
      "helper2.scout.mcp-registry.interval=PT0S",
      "helper2.scout.enabled=false",
    })
@AutoConfigureMockMvc
@Import({
  TestcontainersConfiguration.class,
  ScoutPipelineIT.FakeLlm.class,
  ai.helper2.api.SameOriginRequests.class,
  ai.helper2.api.SessionsComeFromASignIn.class
})
class ScoutPipelineIT {

  @Autowired GetResourceDetail resources;

  static final FixtureServer REGISTRY = new FixtureServer();
  static final AtomicReference<String> LLM_REPLY = new AtomicReference<>("{}");
  static final java.util.concurrent.atomic.AtomicInteger LLM_CALLS =
      new java.util.concurrent.atomic.AtomicInteger();
  static final String REPO = "https://github.com/helper2-test/pg-bridge";

  @DynamicPropertySource
  static void registry(DynamicPropertyRegistry r) {
    r.add("helper2.sources.mcp-registry.base-url", REGISTRY::baseUrl);
    r.add("helper2.sources.mcp-registry.enabled", () -> "true");
  }

  @AfterAll
  static void stop() {
    REGISTRY.close();
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class FakeLlm {
    @Bean
    ChatModel chatModel() {
      return new ChatModel() {
        @Override
        public ChatResponse call(Prompt prompt) {
          LLM_CALLS.incrementAndGet();
          String text = prompt.getContents();
          String evidenceId = text.replaceAll("(?s).*evidenceId=\"([0-9a-f-]{36})\".*", "$1");
          return new ChatResponse(
              List.of(
                  new Generation(
                      new AssistantMessage(LLM_REPLY.get().replace("EVID", evidenceId)))));
        }
      };
    }
  }

  @Autowired MockMvc mvc;

  @Autowired EnrichmentBacklog backlog;

  @Autowired MeterRegistry meters;

  @Autowired LockProvider locks;

  @Autowired JdbcClient jdbc;

  @BeforeEach
  void runLockIsFree() throws InterruptedException {
    Instant deadline = Instant.now().plusSeconds(60);
    while (!runLockReleased()) {
      if (Instant.now().isAfter(deadline)) {
        throw new AssertionError(
            "scout-run lock still held after 60 s: "
                + jdbc.sql(
                        "SELECT locked_by, locked_at, lock_until FROM shedlock WHERE name = 'scout-run'")
                    .query()
                    .listOfRows());
      }
      Thread.sleep(50);
    }
  }

  @Test
  void collectsIndexesEnrichesAndOnlyPublishesEvidencedClaims() throws Exception {
    long runsBefore = succeededRuns();
    REGISTRY.route(
        "/v0.1/servers",
        FixtureServer.Reply.ok(
            """
            {"servers":[{"server":{"name":"io.github.helper2-test/pg-bridge","title":"PG Bridge",
               "description":"MCP server that lets Claude Desktop query PostgreSQL databases read-only.","version":"1.2.0",
               "repository":{"url":"%s","source":"github"}},
              "_meta":{"io.modelcontextprotocol.registry/official":{"isLatest":true,
               "publishedAt":"2026-03-01T00:00:00Z","updatedAt":"2026-09-01T00:00:00Z"}}}],
             "metadata":{"count":1}}\
            """
                .formatted(REPO)));
    LLM_REPLY.set(
        """
        {"fields":[
           {"field":"platforms","value":"desktop","citations":[{"evidenceId":"EVID","excerpt":"lets Claude Desktop query PostgreSQL"}]},
           {"field":"pricing","value":"FREE","citations":[{"evidenceId":"EVID","excerpt":"free for commercial use forever"}]}
         ],
         "relationships":[
           {"kind":"COMPATIBLE_WITH","target":"Claude Desktop","citations":[{"evidenceId":"EVID","excerpt":"lets Claude Desktop query PostgreSQL"}]}
         ],
         "summary":[
           {"text":"Read-only MCP access to PostgreSQL.","citations":[{"evidenceId":"EVID","excerpt":"MCP server that lets Claude Desktop query PostgreSQL databases read-only"}]},
           {"text":"Trusted by 10,000 companies.","citations":[{"evidenceId":"EVID","excerpt":"Trusted by 10,000 companies"}]}
         ]}\
        """);

    var admin = oauth2Login().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    String runs = runMcpRegistry();
    assertThat(JsonPath.<Integer>read(runs, "$.itemsSeen")).isEqualTo(1);
    assertThat(JsonPath.<String>read(runs, "$.status")).isEqualTo("SUCCEEDED");
    assertThat(JsonPath.<Integer>read(runs, "$.enrichmentsQueued")).isEqualTo(1);
    assertThat(succeededRuns()).isEqualTo(runsBefore + 1);
    assertThat(meters.get("http.client.requests").tag("uri", "/v0.1/servers").timer().count())
        .isPositive();
    assertThat(meters.get("helper2.scout.enrichment").tag("outcome", "PARTIAL").counter().count())
        .isEqualTo(1);

    mvc.perform(get("/api/v1/search").param("q", "pg bridge postgresql").with(admin))
        .andExpect(
            jsonPath("$.results[?(@.title=='PG Bridge')].sources[0].sourceId")
                .value("helper2-index"))
        .andExpect(jsonPath("$.results[?(@.title=='PG Bridge')].freshness").value("INDEXED"))
        .andExpect(
            jsonPath("$.results[?(@.title=='PG Bridge')].timeline.published.sourceId")
                .value("mcp-registry"));

    String id = ResourceId.of(CanonicalUrl.of(REPO)).toString();
    ResourceDetail detail = resources.find(UUID.fromString(id)).orElseThrow();
    assertThat(detail.enrichmentStatus()).isEqualTo("PARTIAL");
    List<ResourceDetail.Assertion> ai =
        detail.assertions().stream().filter(a -> a.origin() == ValueOrigin.AI_EXTRACTED).toList();
    assertThat(ai).extracting(ResourceDetail.Assertion::field).containsExactly("platforms");
    assertThat(ai.getFirst().verification()).isEqualTo(Verification.VERIFIED);
    assertThat(ai)
        .extracting(a -> a.evidence().getFirst().excerpt())
        .containsExactly("lets Claude Desktop query PostgreSQL");
    assertThat(detail.summary().claims())
        .extracting(ResourceDetail.SummaryClaim::text)
        .containsExactly("Read-only MCP access to PostgreSQL.");
    assertThat(detail.relationships())
        .extracting(ResourceDetail.Relationship::verification)
        .containsExactly(Verification.UNVERIFIED);
    assertThat(detail.toString())
        .doesNotContain("10,000 companies")
        .doesNotContain("free for commercial use");

    String review =
        mvc.perform(
                get("/api/v1/admin/review-items")
                    .param("status", "OPEN")
                    .param("size", "100")
                    .with(admin))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(JsonPath.<List<String>>read(review, "$.items[?(@.resourceId=='" + id + "')].kind"))
        .contains("ENRICHMENT_VALIDATION", "RELATIONSHIP_CANDIDATE");

    String again = runMcpRegistry();
    assertThat(JsonPath.<Integer>read(again, "$.itemsChanged")).isZero();
    assertThat(REGISTRY.requests.getLast()).contains("updated_since=");
  }

  private long succeededRuns() {
    var timer =
        meters
            .find("helper2.scout.run")
            .tags("source", "mcp-registry", "status", "succeeded", "error", "none")
            .timer();
    return timer == null ? 0 : timer.count();
  }

  private static String rerunServer(String version, String description, String updatedAt) {
    return """
    {"servers":[{"server":{"name":"io.github.helper2-test/rerun","title":"Rerun Server",
       "description":"%s","version":"%s",
       "repository":{"url":"https://github.com/helper2-test/rerun","source":"github"}},
      "_meta":{"io.modelcontextprotocol.registry/official":{"isLatest":true,
       "publishedAt":"2026-03-01T00:00:00Z","updatedAt":"%s"}}}],
     "metadata":{"count":1}}\
    """
        .formatted(description, version, updatedAt);
  }

  private String runMcpRegistry() throws Exception {
    var admin = oauth2Login().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    String started =
        mvc.perform(
                post("/api/v1/admin/scout-runs")
                    .with(admin)
                    .with(csrf().asHeader())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"sourceId\":\"mcp-registry\"}"))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$[0].sourceId").value("mcp-registry"))
            .andExpect(jsonPath("$[0].status").value("RUNNING"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String id = JsonPath.read(started, "$[0].id");
    Instant deadline = Instant.now().plusSeconds(30);
    while (Instant.now().isBefore(deadline)) {
      String listed =
          mvc.perform(get("/api/v1/admin/scout-runs").with(admin))
              .andExpect(status().isOk())
              .andReturn()
              .getResponse()
              .getContentAsString();
      List<Map<String, Object>> run = JsonPath.read(listed, "$[?(@.id=='" + id + "')]");
      assertThat(run).hasSize(1);
      if (!"RUNNING".equals(run.getFirst().get("status")) && runLockReleased()) {
        return JsonPath.parse(run.getFirst()).jsonString();
      }
      Thread.sleep(50);
    }
    throw new AssertionError("Scout run " + id + " still RUNNING after 30 s");
  }

  private boolean runLockReleased() {
    return jdbc.sql(
            "SELECT lock_until <= timezone('utc', now()) FROM shedlock WHERE name = 'scout-run'")
        .query(Boolean.class)
        .optional()
        .orElse(true);
  }

  private List<String> aiFields(String id) {
    return resources.find(UUID.fromString(id)).orElseThrow().assertions().stream()
        .filter(a -> a.origin() == ValueOrigin.AI_EXTRACTED)
        .map(ResourceDetail.Assertion::value)
        .toList();
  }

  @Test
  void runNowIsRefusedWhileAnotherReplicaHoldsTheRunLock() throws Exception {
    REGISTRY.route(
        "/v0.1/servers", FixtureServer.Reply.ok("{\"servers\":[],\"metadata\":{\"count\":0}}"));
    SimpleLock otherReplica =
        locks
            .lock(
                new LockConfiguration(
                    Instant.now(), "scout-run", Duration.ofMinutes(1), Duration.ZERO))
            .orElseThrow();
    var admin = oauth2Login().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    try {
      mvc.perform(
              post("/api/v1/admin/scout-runs")
                  .with(admin)
                  .with(csrf().asHeader())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"sourceId\":\"mcp-registry\"}"))
          .andExpect(status().isConflict());
    } finally {
      otherReplica.unlock();
    }
    runMcpRegistry();
  }

  private long enrichmentRuns(String resourceId) {
    return jdbc.sql("SELECT count(*) FROM enrichment_run WHERE resource_id = CAST(:id AS uuid)")
        .param("id", resourceId)
        .query(Long.class)
        .single();
  }

  @Test
  void anUnchangedTextIsNotEnrichedAgainAndANewRunReplacesTheOldFields() throws Exception {
    String desktop = "MCP server that lets Claude Desktop rerun failed jobs.";
    String id = ResourceId.of(CanonicalUrl.of("https://github.com/helper2-test/rerun")).toString();
    REGISTRY.route(
        "/v0.1/servers",
        FixtureServer.Reply.ok(rerunServer("1.0.0", desktop, "2026-10-01T00:00:00Z")));
    LLM_REPLY.set(
        """
        {"fields":[{"field":"platforms","value":"desktop","citations":[{"evidenceId":"EVID","excerpt":"lets Claude Desktop rerun"}]}]}\
        """);
    runMcpRegistry();
    assertThat(aiFields(id)).containsExactly("desktop");

    long runs = enrichmentRuns(id);
    REGISTRY.route(
        "/v0.1/servers",
        FixtureServer.Reply.ok(rerunServer("1.0.1", desktop, "2026-10-02T00:00:00Z")));
    assertThat(JsonPath.<Integer>read(runMcpRegistry(), "$.enrichmentsQueued")).isZero();
    assertThat(enrichmentRuns(id)).isEqualTo(runs);

    String cli = "MCP server with a command-line tool that reruns failed jobs.";
    REGISTRY.route(
        "/v0.1/servers", FixtureServer.Reply.ok(rerunServer("1.1.0", cli, "2026-10-03T00:00:00Z")));
    LLM_REPLY.set(
        """
        {"fields":[{"field":"platforms","value":"cli","citations":[{"evidenceId":"EVID","excerpt":"a command-line tool that reruns"}]}]}\
        """);
    assertThat(JsonPath.<Integer>read(runMcpRegistry(), "$.enrichmentsQueued")).isEqualTo(1);
    assertThat(aiFields(id)).containsExactly("cli");
    assertThat(
            jdbc.sql("SELECT count(*) FROM evidence WHERE excerpt = 'lets Claude Desktop rerun'")
                .query(Long.class)
                .single())
        .isZero();
    assertThat(
            jdbc.sql(
                    "SELECT count(*) FROM evidence WHERE excerpt = 'a command-line tool that reruns'")
                .query(Long.class)
                .single())
        .isOne();
  }

  @Test
  void resourcesLeftOverByTheBudgetAreEnrichedByLaterRuns() throws Exception {
    String servers =
        java.util.stream.IntStream.range(0, 3)
            .mapToObj(i ->
                """
                {"server":{"name":"io.github.helper2-test/backlog-%d","title":"Backlog Server %d",
                   "description":"MCP server number %d that exposes a ticket tracker to AI agents.","version":"1.0.0",
                   "repository":{"url":"https://github.com/helper2-test/backlog-%d","source":"github"}},
                 "_meta":{"io.modelcontextprotocol.registry/official":{"isLatest":true,
                   "publishedAt":"2026-03-01T00:00:00Z","updatedAt":"2026-10-01T00:00:00Z"}}}\
                """
                    .formatted(i, i, i, i))
            .collect(java.util.stream.Collectors.joining(","));
    REGISTRY.route(
        "/v0.1/servers",
        FixtureServer.Reply.ok("{\"servers\":[" + servers + "],\"metadata\":{\"count\":3}}"));
    LLM_REPLY.set("{\"fields\":[],\"relationships\":[],\"summary\":[]}");
    List<UUID> ids =
        java.util.stream.IntStream.range(0, 3)
            .mapToObj(i ->
                ResourceId.of(CanonicalUrl.of("https://github.com/helper2-test/backlog-" + i))
                    .getValue())
            .toList();

    String first = runMcpRegistry();
    assertThat(JsonPath.<Integer>read(first, "$.itemsChanged")).isEqualTo(3);
    assertThat(JsonPath.<Integer>read(first, "$.enrichmentsQueued")).isEqualTo(1);
    List<UUID> stranded = backlog.pending(100).stream().filter(ids::contains).toList();
    assertThat(stranded).hasSize(2);
    makeOldestPending(stranded);

    REGISTRY.route(
        "/v0.1/servers", FixtureServer.Reply.ok("{\"servers\":[],\"metadata\":{\"count\":0}}"));
    assertThat(JsonPath.<Integer>read(runMcpRegistry(), "$.itemsChanged")).isZero();
    assertThat(backlog.pending(100).stream().filter(ids::contains).toList()).hasSize(1);
  }

  private void makeOldestPending(List<UUID> resources) {
    jdbc.sql("UPDATE resource SET first_discovered_at = '2000-01-01T00:00:00Z' WHERE id IN (:ids)")
        .param("ids", resources)
        .update();
  }
}
