package ai.helper2.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.helper2.setup.Material;
import ai.helper2.setup.MaterialKind;
import ai.helper2.setup.ToolMaterials;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * POST /resources/{id}/guide without a model (the test profile has none): the documents are found or not, and the
 * answer says why there are no steps. The validator and the model call are unit-tested.
 */
@Import(ToolGuideApiIT.FakeDocs.class)
class ToolGuideApiIT extends ApiTestBase {

    static final AtomicReference<ToolMaterials> DOCS = new AtomicReference<>();

    @Autowired
    JdbcClient jdbc;

    @TestConfiguration(proxyBeanMethods = false)
    static class FakeDocs {
        @Bean
        @Primary
        ToolMaterials fakeToolMaterials() {
            return ref -> DOCS.get().load(ref);
        }
    }

    @BeforeEach
    void docs() {
        DOCS.set(ref -> "acme/readme-mcp".equals(ref.getGithubRepo())
            ? List.of(new Material("readme", MaterialKind.README, URI.create("https://github.com/acme/readme-mcp"), Instant.now(), """
                # readme-mcp
                ```json
                { "mcpServers": { "readme-mcp": { "command": "npx", "args": ["-y", "readme-mcp"], "env": { "API_TOKEN": "x" } } } }
                ```
                """))
            : List.of());
    }

    @Test
    void withoutAModelTheGuideSaysSoAndKeepsTheDocuments() throws Exception {
        UUID id = seed("https://github.com/acme/readme-mcp", "readme-mcp");
        String body = mvc.perform(post("/api/v1/resources/{id}/guide", id).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        ContractValidator.assertMatches("ToolGuide", body);
        assertThat((String) JsonPath.read(body, "$.status")).isEqualTo("LLM_UNAVAILABLE");
        assertThat((List<String>) JsonPath.read(body, "$.documents[*].url")).containsExactly("https://github.com/acme/readme-mcp");
    }

    @Test
    void noDocumentsIsNoMaterial_questionTooLongIs400_withoutCsrfIs403() throws Exception {
        UUID id = seed("https://example.com/plain-product", "plain-product");
        String body = mvc.perform(post("/api/v1/resources/{id}/guide", id).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"How do I install it?\"}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat((String) JsonPath.read(body, "$.status")).isEqualTo("NO_MATERIAL");
        assertThat((String) JsonPath.read(body, "$.question")).isEqualTo("How do I install it?");
        mvc.perform(post("/api/v1/resources/{id}/guide", id).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"" + "x".repeat(501) + "\"}"))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/resources/{id}/guide", id).contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isForbidden());
    }

    @Test
    void setupFallsBackToTheReadmeConfigExampleWithoutARegistryEntry() throws Exception {
        UUID id = seed("https://github.com/acme/readme-mcp", "readme-mcp");
        String body = mvc.perform(get("/api/v1/resources/{id}/setup", id))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        ContractValidator.assertMatches("SetupGuide", body);
        assertThat((List<String>) JsonPath.read(body, "$.options[*].kind")).containsExactly("COMMAND");
        assertThat((String) JsonPath.read(body, "$.sources[0].sourceId")).isEqualTo("readme");
        assertThat((List<String>) JsonPath.read(body, "$.clients[?(@.id=='claude-code')].snippets[0].text"))
            .containsExactly("claude mcp add --env 'API_TOKEN=<API_TOKEN>' --transport stdio readme-mcp -- npx -y readme-mcp");
    }

    private UUID seed(String url, String title) throws Exception {
        FAKE.set(q -> List.of(fakeHit(url, title, title + " server", ResourceType.MCP_SERVER, "MIT", null, null, 0)));
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
