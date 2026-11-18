package ai.helper2.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

class AiCheckApiIT extends ApiTestBase {

  @Autowired JdbcClient jdbc;

  final List<UUID> created = new ArrayList<>();

  @AfterEach
  void clean() {
    created.forEach(id -> jdbc.sql("DELETE FROM resource WHERE id = :id").param("id", id).update());
  }

  private UUID resource(String title, String description, String tag, double popularity) {
    UUID id = UUID.randomUUID();
    jdbc.sql(
            """
            INSERT INTO resource (id, canonical_url, url, title, description, description_origin, type, audience,
                                  data_origin, popularity)
            VALUES (:id, :url, :url, :title, :description, 'SOURCE_PROVIDED', 'LIBRARY', 'BUILD_WITH_AI', 'INDEXED',
                    :popularity)
            """)
        .param("id", id)
        .param("url", "https://ai-check.example/" + id)
        .param("title", title)
        .param("description", description)
        .param("popularity", popularity)
        .update();
    jdbc.sql("INSERT INTO resource_label (resource_id, kind, value) VALUES (:id, 'TAG', :tag)")
        .param("id", id)
        .param("tag", tag)
        .update();
    created.add(id);
    return id;
  }

  private void sourceText(UUID resource, String text) {
    UUID record = UUID.randomUUID();
    jdbc.sql(
            """
            INSERT INTO source_record (id, source_id, external_id, resource_id, source_url, canonical_url, title,
                                       description, type, retrieved_at, fingerprint)
            VALUES (:rec, 'curated', :rec::text, :id, :url, :url, 'AI check', :text, 'LIBRARY', now(), 'fp')
            """)
        .param("rec", record)
        .param("id", resource)
        .param("url", "https://ai-check.example/record/" + record)
        .param("text", text)
        .update();
  }

  @Test
  void theResourcesThatFailTheWordRuleAreListedAndNothingChanges() throws Exception {
    UUID notAi =
        resource("mongodb-connection-string-url", "MongoDB connection strings", "prompt", 0.9);
    UUID ai = resource("ollama-js", "Ollama Javascript library", "llm", 0.9);
    UUID queued = resource("agent-base", "HTTP agent base class", "agent", 0.9);
    sourceText(queued, "Turn a function into an http.Agent instance for Node.js requests");
    var admin = oauth2Login().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));

    mvc.perform(get("/api/v1/admin/ai-check")).andExpect(status().isUnauthorized());
    String body =
        mvc.perform(get("/api/v1/admin/ai-check").param("size", "2000").with(admin))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    ContractValidator.assertMatches("AiCheckPage", body);

    List<Map<String, Object>> rows = JsonPath.read(body, "$.notAboutAi");
    assertThat(rows).extracting(r -> r.get("resourceId")).contains(notAi.toString());
    assertThat(rows).extracting(r -> r.get("resourceId")).doesNotContain(ai.toString());
    assertThat(rows)
        .filteredOn(r -> r.get("resourceId").equals(notAi.toString()))
        .singleElement()
        .as("without source text it is not in the enrichment queue, so it cannot be skipped")
        .satisfies(r -> assertThat(r).containsEntry("waiting", false));
    assertThat(rows)
        .filteredOn(r -> r.get("resourceId").equals(queued.toString()))
        .singleElement()
        .satisfies(r -> assertThat(r).containsEntry("waiting", true));
    assertThat(
            jdbc.sql("SELECT count(*) FROM resource WHERE id = :id")
                .param("id", notAi)
                .query(Long.class)
                .single())
        .isEqualTo(1);

    mvc.perform(get("/api/v1/admin/ai-check").param("after", "not-a-cursor").with(admin))
        .andExpect(status().isBadRequest());
  }

  @Test
  void theNextCursorContinuesAfterTheLastRowRead() throws Exception {
    resource("one", "Weak words only: vector", "vector", 0.5);
    resource("two", "Weak words only: prompt", "prompt", 0.5);
    var admin = oauth2Login().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    String first =
        mvc.perform(get("/api/v1/admin/ai-check").param("size", "1").with(admin))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(JsonPath.<Integer>read(first, "$.checked")).isEqualTo(1);
    String next = JsonPath.read(first, "$.next");
    assertThat(next).isNotBlank();
    mvc.perform(get("/api/v1/admin/ai-check").param("size", "1").param("after", next).with(admin))
        .andExpect(status().isOk());
  }
}
