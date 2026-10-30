package ai.helper2.scout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.helper2.TestcontainersConfiguration;
import ai.helper2.shared.CanonicalUrl;
import ai.helper2.shared.ResourceId;
import ai.helper2.testing.FixtureServer;
import com.jayway.jsonpath.JsonPath;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.junit.jupiter.api.AfterAll;
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

/**
 * Scout end to end: fixture MCP Registry -> collection -> PostgreSQL index -> LLM proposal (fake
 * ChatModel, no network) -> deterministic validation -> detail page and review queue.
 */
@SpringBootTest(properties = {
    "helper2.sources.github.enabled=false", "helper2.sources.huggingface.enabled=false",
    "helper2.sources.npm.enabled=false", "helper2.sources.mcp-registry.enabled=true", "helper2.sources.arxiv.enabled=false", "helper2.sources.tavily.enabled=false",
    "helper2.sources.hf-trending.enabled=false", "helper2.sources.tavily-scout.enabled=false", "helper2.sources.course-scout.enabled=false",
    "helper2.sources.hf-provider-orgs.enabled=false", "helper2.sources.ai-deadlines.enabled=false",
    "spring.ai.model.chat=fake", "helper2.llm.reasoning-model=fake-super", "logging.structured.format.console=",
    // One enrichment per run, so the backlog test can see resources left over by the budget.
    "helper2.scout.max-enrichments-per-run=1",
    // Back-to-back admin runs test incremental sync; the hourly MCP interval is tested in McpRegistryCollectorTest.
    "helper2.scout.mcp-registry.interval=PT0S",
})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, ScoutPipelineIT.FakeLlm.class})
class ScoutPipelineIT {

    static final FixtureServer REGISTRY = new FixtureServer();
    static final AtomicReference<String> LLM_REPLY = new AtomicReference<>("{}");
    static final java.util.concurrent.atomic.AtomicInteger LLM_CALLS = new java.util.concurrent.atomic.AtomicInteger();
    static final String REPO = "https://github.com/helper2-test/pg-bridge";

    @DynamicPropertySource
    static void registry(DynamicPropertyRegistry r) {
        r.add("helper2.sources.mcp-registry.base-url", REGISTRY::baseUrl);
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
                    // Echo the real evidence id the adapter put in the prompt, like a model would.
                    String evidenceId = text.replaceAll("(?s).*evidenceId=\"([0-9a-f-]{36})\".*", "$1");
                    return new ChatResponse(List.of(new Generation(new AssistantMessage(LLM_REPLY.get().replace("EVID", evidenceId)))));
                }
            };
        }
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    EnrichmentBacklog backlog;

    @Autowired
    MeterRegistry meters;

    @Autowired
    LockProvider locks;

    @Autowired
    JdbcClient jdbc;

    @Test
    void collectsIndexesEnrichesAndOnlyPublishesEvidencedClaims() throws Exception {
        // Other tests of this class share the meters: count from here.
        long runsBefore = succeededRuns();
        REGISTRY.route("/v0.1/servers", FixtureServer.Reply.ok("""
            {"servers":[{"server":{"name":"io.github.helper2-test/pg-bridge","title":"PG Bridge",
               "description":"MCP server that lets Claude Desktop query PostgreSQL databases read-only.","version":"1.2.0",
               "repository":{"url":"%s","source":"github"}},
              "_meta":{"io.modelcontextprotocol.registry/official":{"isLatest":true,
               "publishedAt":"2026-03-01T00:00:00Z","updatedAt":"2026-09-01T00:00:00Z"}}}],
             "metadata":{"count":1}}""".formatted(REPO)));
        LLM_REPLY.set("""
            {"fields":[
               {"field":"platforms","value":"desktop","citations":[{"evidenceId":"EVID","excerpt":"lets Claude Desktop query PostgreSQL"}]},
               {"field":"pricing","value":"FREE","citations":[{"evidenceId":"EVID","excerpt":"free for commercial use forever"}]}
             ],
             "relationships":[
               {"kind":"COMPATIBLE_WITH","target":"Claude Desktop","citations":[{"evidenceId":"EVID","excerpt":"lets Claude Desktop query PostgreSQL"}]}
             ],
             "summary":[
               {"text":"Read-only MCP access to PostgreSQL.","citations":[{"evidenceId":"EVID","excerpt":"query PostgreSQL databases read-only"}]},
               {"text":"Trusted by 10,000 companies.","citations":[{"evidenceId":"EVID","excerpt":"Trusted by 10,000 companies"}]}
             ]}""");

        var admin = oauth2Login().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
        String runs = mvc.perform(post("/api/v1/admin/scout-runs").with(admin).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"sourceId\":\"mcp-registry\"}"))
            .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<Integer>read(runs, "$[0].itemsSeen")).isEqualTo(1);
        assertThat(JsonPath.<String>read(runs, "$[0].status")).isEqualTo("SUCCEEDED");
        assertThat(JsonPath.<Integer>read(runs, "$[0].enrichmentsQueued")).isEqualTo(1);
        // Boot's observation registry records the run and the registry call as meters.
        assertThat(succeededRuns()).isEqualTo(runsBefore + 1);
        assertThat(meters.get("http.client.requests").tag("uri", "/v0.1/servers").timer().count()).isPositive();
        assertThat(meters.get("helper2.scout.enrichment").tag("outcome", "PARTIAL").counter().count()).isEqualTo(1);

        // Indexed resource is now searchable through the Helper2 index (not a live MCP call).
        mvc.perform(get("/api/v1/search").param("q", "pg bridge postgresql"))
            .andExpect(jsonPath("$.results[?(@.title=='PG Bridge')].sources[0].sourceId").value("helper2-index"))
            .andExpect(jsonPath("$.results[?(@.title=='PG Bridge')].freshness").value("INDEXED"))
            .andExpect(jsonPath("$.results[?(@.title=='PG Bridge')].timeline.published.sourceId").value("mcp-registry"));

        String id = ResourceId.of(CanonicalUrl.of(REPO)).toString();
        String detail = mvc.perform(get("/api/v1/resources/" + id)).andExpect(status().isOk())
            .andExpect(jsonPath("$.enrichmentStatus").value("PARTIAL"))
            .andReturn().getResponse().getContentAsString();
        List<Map<String, Object>> ai = JsonPath.read(detail, "$.assertions[?(@.origin=='AI_EXTRACTED')]");
        assertThat(ai).extracting(a -> a.get("field")).containsExactly("platforms");
        assertThat(ai.getFirst().get("verification")).isEqualTo("VERIFIED");
        assertThat(JsonPath.<List<String>>read(detail, "$.assertions[?(@.origin=='AI_EXTRACTED')].evidence[0].excerpt"))
            .containsExactly("lets Claude Desktop query PostgreSQL");
        assertThat(JsonPath.<List<String>>read(detail, "$.summary.claims[*].text")).containsExactly("Read-only MCP access to PostgreSQL.");
        // Claude Desktop is not a known resource in this catalog, so the relationship is only a candidate.
        assertThat(JsonPath.<List<String>>read(detail, "$.relationships[*].verification")).containsExactly("UNVERIFIED");
        assertThat(detail).doesNotContain("10,000 companies").doesNotContain("free for commercial use");

        String review = mvc.perform(get("/api/v1/admin/review-items").param("status", "OPEN").param("size", "100").with(admin))
            .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(review, "$.items[?(@.resourceId=='" + id + "')].kind"))
            .contains("ENRICHMENT_VALIDATION", "RELATIONSHIP_CANDIDATE");

        // Re-running is idempotent: same fingerprint, nothing changed, no new enrichment.
        String again = mvc.perform(post("/api/v1/admin/scout-runs").with(admin).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"sourceId\":\"mcp-registry\"}"))
            .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<Integer>read(again, "$[0].itemsChanged")).isZero();
        assertThat(REGISTRY.requests.getLast()).contains("updated_since=");
    }

    private long succeededRuns() {
        var timer = meters.find("helper2.scout.run").tags("source", "mcp-registry", "status", "succeeded", "error", "none").timer();
        return timer == null ? 0 : timer.count();
    }

    private static String rerunServer(String version, String description, String updatedAt) {
        return """
            {"servers":[{"server":{"name":"io.github.helper2-test/rerun","title":"Rerun Server",
               "description":"%s","version":"%s",
               "repository":{"url":"https://github.com/helper2-test/rerun","source":"github"}},
              "_meta":{"io.modelcontextprotocol.registry/official":{"isLatest":true,
               "publishedAt":"2026-03-01T00:00:00Z","updatedAt":"%s"}}}],
             "metadata":{"count":1}}""".formatted(description, version, updatedAt);
    }

    private String runMcpRegistry() throws Exception {
        var admin = oauth2Login().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
        return mvc.perform(post("/api/v1/admin/scout-runs").with(admin).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"sourceId\":\"mcp-registry\"}"))
            .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
    }

    private List<String> aiFields(String id) throws Exception {
        String detail = mvc.perform(get("/api/v1/resources/" + id)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(detail, "$.assertions[?(@.origin=='AI_EXTRACTED')].value");
    }

    @Test
    void runNowIsRefusedWhileAnotherReplicaHoldsTheRunLock() throws Exception {
        REGISTRY.route("/v0.1/servers", FixtureServer.Reply.ok("{\"servers\":[],\"metadata\":{\"count\":0}}"));
        SimpleLock otherReplica = locks.lock(new LockConfiguration(Instant.now(), "scout-run", Duration.ofMinutes(1), Duration.ZERO))
            .orElseThrow();
        var admin = oauth2Login().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
        try {
            mvc.perform(post("/api/v1/admin/scout-runs").with(admin).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON).content("{\"sourceId\":\"mcp-registry\"}"))
                .andExpect(status().isConflict());
        } finally {
            otherReplica.unlock();
        }
        runMcpRegistry();
    }

    private long enrichmentRuns(String resourceId) {
        return jdbc.sql("SELECT count(*) FROM enrichment_run WHERE resource_id = CAST(:id AS uuid)").param("id", resourceId)
            .query(Long.class).single();
    }

    @Test
    void anUnchangedTextIsNotEnrichedAgainAndANewRunReplacesTheOldFields() throws Exception {
        String desktop = "MCP server that lets Claude Desktop rerun failed jobs.";
        String id = ResourceId.of(CanonicalUrl.of("https://github.com/helper2-test/rerun")).toString();
        REGISTRY.route("/v0.1/servers", FixtureServer.Reply.ok(rerunServer("1.0.0", desktop, "2026-10-01T00:00:00Z")));
        LLM_REPLY.set("""
            {"fields":[{"field":"platforms","value":"desktop","citations":[{"evidenceId":"EVID","excerpt":"lets Claude Desktop rerun"}]}]}""");
        runMcpRegistry();
        assertThat(aiFields(id)).containsExactly("desktop");

        // A new version with the same text: the record changed, the model input did not. (The run's one
        // enrichment slot then goes to the backlog other tests left, so the model may still be called.)
        long runs = enrichmentRuns(id);
        REGISTRY.route("/v0.1/servers", FixtureServer.Reply.ok(rerunServer("1.0.1", desktop, "2026-10-02T00:00:00Z")));
        assertThat(JsonPath.<Integer>read(runMcpRegistry(), "$[0].enrichmentsQueued")).isZero();
        assertThat(enrichmentRuns(id)).isEqualTo(runs);

        // New text: enriched again, and only the latest run's fields are shown.
        String cli = "MCP server with a command-line tool that reruns failed jobs.";
        REGISTRY.route("/v0.1/servers", FixtureServer.Reply.ok(rerunServer("1.1.0", cli, "2026-10-03T00:00:00Z")));
        LLM_REPLY.set("""
            {"fields":[{"field":"platforms","value":"cli","citations":[{"evidenceId":"EVID","excerpt":"a command-line tool that reruns"}]}]}""");
        assertThat(JsonPath.<Integer>read(runMcpRegistry(), "$[0].enrichmentsQueued")).isEqualTo(1);
        assertThat(aiFields(id)).containsExactly("cli");
        // The replaced field's evidence goes with it.
        assertThat(jdbc.sql("SELECT count(*) FROM evidence WHERE excerpt = 'lets Claude Desktop rerun'").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM evidence WHERE excerpt = 'a command-line tool that reruns'").query(Long.class).single()).isOne();
    }

    @Test
    void resourcesLeftOverByTheBudgetAreEnrichedByLaterRuns() throws Exception {
        String servers = java.util.stream.IntStream.range(0, 3).mapToObj(i -> """
            {"server":{"name":"io.github.helper2-test/backlog-%d","title":"Backlog Server %d",
               "description":"MCP server number %d that exposes a ticket tracker to AI agents.","version":"1.0.0",
               "repository":{"url":"https://github.com/helper2-test/backlog-%d","source":"github"}},
             "_meta":{"io.modelcontextprotocol.registry/official":{"isLatest":true,
               "publishedAt":"2026-03-01T00:00:00Z","updatedAt":"2026-10-01T00:00:00Z"}}}""".formatted(i, i, i, i))
            .collect(java.util.stream.Collectors.joining(","));
        REGISTRY.route("/v0.1/servers", FixtureServer.Reply.ok("{\"servers\":[" + servers + "],\"metadata\":{\"count\":3}}"));
        LLM_REPLY.set("{\"fields\":[],\"relationships\":[],\"summary\":[]}");
        List<UUID> ids = java.util.stream.IntStream.range(0, 3)
            .mapToObj(i -> ResourceId.of(CanonicalUrl.of("https://github.com/helper2-test/backlog-" + i)).getValue()).toList();
        var admin = oauth2Login().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));

        mvc.perform(post("/api/v1/admin/scout-runs").with(admin).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"sourceId\":\"mcp-registry\"}"))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$[0].itemsChanged").value(3))
            .andExpect(jsonPath("$[0].enrichmentsQueued").value(1));
        List<UUID> stranded = backlog.pending(100).stream().filter(ids::contains).toList();
        assertThat(stranded).hasSize(2);

        // Nothing changes at the source, yet the next run spends its budget on the backlog.
        REGISTRY.route("/v0.1/servers", FixtureServer.Reply.ok("{\"servers\":[],\"metadata\":{\"count\":0}}"));
        mvc.perform(post("/api/v1/admin/scout-runs").with(admin).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"sourceId\":\"mcp-registry\"}"))
            .andExpect(jsonPath("$[0].itemsChanged").value(0));
        assertThat(backlog.pending(100).stream().filter(ids::contains).toList()).hasSize(1);

        // A bare title (no description) is never worth an LLM call.
        UUID withText = ids.getFirst();
        assertThat(backlog.eligible(List.of(UUID.randomUUID(), withText))).containsExactly(withText);
    }
}
