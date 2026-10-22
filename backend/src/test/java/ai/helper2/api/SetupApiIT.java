package ai.helper2.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.helper2.setup.McpServerSpec;
import ai.helper2.setup.McpServerSpecs;
import ai.helper2.setup.RunOption;
import ai.helper2.setup.Setting;
import ai.helper2.setup.SettingKind;
import ai.helper2.setup.SetupUnavailable;
import ai.helper2.setup.Transport;
import ai.helper2.shared.ResourceType;
import com.jayway.jsonpath.JsonPath;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;

/** GET /resources/{id}/setup: snippets from the resource's MCP Registry entry, or none when it has no entry. */
@Import(SetupApiIT.FakeRegistry.class)
class SetupApiIT extends ApiTestBase {

    static final AtomicReference<McpServerSpecs> REGISTRY = new AtomicReference<>();

    @TestConfiguration(proxyBeanMethods = false)
    static class FakeRegistry {
        @Bean
        @Primary
        McpServerSpecs fakeMcpServerSpecs() {
            return name -> REGISTRY.get().latest(name);
        }
    }

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void registry() {
        REGISTRY.set(name -> "io.acme/pg".equals(name) ? new McpServerSpec(name, "1.2.0", List.of(
            new RunOption.Remote(Transport.STREAMABLE_HTTP, URI.create("https://mcp.acme.dev/mcp"), List.of()),
            new RunOption.Package("npm", "@acme/pg-mcp", "1.2.0", Transport.STDIO, null, List.of(), List.of(),
                List.of(new Setting("DATABASE_URL", SettingKind.ENV, "Connection string", true, true, null)))),
            URI.create("https://registry.modelcontextprotocol.io/v0.1/servers/io.acme%2Fpg/versions/latest"),
            Instant.parse("2026-10-27T20:00:00Z")) : null);
    }

    @Test
    void mcpServerFromTheRegistryGetsASnippetPerClient() throws Exception {
        UUID id = seed("https://github.com/acme/pg-mcp", "Acme PG");
        registryRecord(id, "io.acme/pg");

        String body = mvc.perform(get("/api/v1/resources/{id}/setup", id))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "max-age=3600, public"))
            .andReturn().getResponse().getContentAsString();
        ContractValidator.assertMatches("SetupGuide", body);

        assertThat((String) JsonPath.read(body, "$.version")).isEqualTo("1.2.0");
        assertThat((List<String>) JsonPath.read(body, "$.options[*].kind")).containsExactly("REMOTE", "PACKAGE");
        assertThat((String) JsonPath.read(body, "$.options[1].runtime")).isEqualTo("npx");
        assertThat((String) JsonPath.read(body, "$.options[1].requires")).isEqualTo("Node.js (npx)");
        assertThat((List<String>) JsonPath.read(body, "$.clients[*].id")).contains("claude-code", "cursor", "vscode", "codex");
        assertThat((List<String>) JsonPath.read(body, "$.clients[?(@.id=='claude-code')].snippets[0].text"))
            .containsExactly("claude mcp add --transport http acme-pg https://mcp.acme.dev/mcp");
        // A secret is never filled in.
        assertThat(body).contains("<DATABASE_URL>");
        assertThat((String) JsonPath.read(body, "$.sources[0].sourceId")).isEqualTo("mcp-registry");
    }

    @Test
    void resourceWithoutARegistryEntryHasNoOptions() throws Exception {
        UUID id = seed("https://github.com/acme/plain-lib", "Plain Lib");
        String body = mvc.perform(get("/api/v1/resources/{id}/setup", id))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        ContractValidator.assertMatches("SetupGuide", body);
        assertThat((List<Object>) JsonPath.read(body, "$.options")).isEmpty();
        assertThat((List<Object>) JsonPath.read(body, "$.clients")).isEmpty();
    }

    @Test
    void registryDownIs503AndUnknownResourceIs404() throws Exception {
        UUID id = seed("https://github.com/acme/down-mcp", "Down MCP");
        registryRecord(id, "io.acme/down");
        REGISTRY.set(name -> { throw new SetupUnavailable("down"); });
        mvc.perform(get("/api/v1/resources/{id}/setup", id)).andExpect(status().isServiceUnavailable());
        mvc.perform(get("/api/v1/resources/{id}/setup", UUID.randomUUID())).andExpect(status().isNotFound());
    }

    /** Adds an MCP Registry record to the resource, copied from the record the search stored. */
    private void registryRecord(UUID id, String name) {
        jdbc.sql("""
                INSERT INTO source (id, name, mode, homepage, attribution)
                VALUES ('mcp-registry', 'Official MCP Registry', 'INDEXED', 'https://registry.modelcontextprotocol.io', 'CC0')
                ON CONFLICT (id) DO NOTHING""").update();
        jdbc.sql("""
                INSERT INTO source_record (id, source_id, external_id, resource_id, source_url, canonical_url, title, type,
                                           retrieved_at, fingerprint)
                SELECT :rid, 'mcp-registry', :name, resource_id, source_url, canonical_url, title, type, retrieved_at, 'f'
                FROM source_record WHERE resource_id = :id LIMIT 1""")
            .param("rid", UUID.randomUUID()).param("name", name).param("id", id).update();
    }

    /** Stores a resource the way a search does, and returns its id. */
    private UUID seed(String url, String title) throws Exception {
        FAKE.set(q -> List.of(fakeHit(url, title, title + " for Postgres", ResourceType.MCP_SERVER, "MIT", null, null, 0)));
        mvc.perform(get("/api/v1/search").param("q", title)).andExpect(status().isOk());
        AtomicReference<UUID> id = new AtomicReference<>();
        eventually(() -> {
            List<UUID> ids = jdbc.sql("SELECT resource_id FROM source_record WHERE source_url = :u").param("u", url).query(UUID.class).list();
            assertThat(ids).isNotEmpty();
            id.set(ids.getFirst());
        });
        return id.get();
    }
}
