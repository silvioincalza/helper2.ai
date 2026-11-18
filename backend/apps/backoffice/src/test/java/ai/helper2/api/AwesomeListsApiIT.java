package ai.helper2.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.helper2.scout.AwesomeListReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

@Import(AwesomeListsApiIT.FakeReader.class)
class AwesomeListsApiIT extends ApiTestBase {

  static final Map<String, Optional<Boolean>> README = new ConcurrentHashMap<>();

  @TestConfiguration(proxyBeanMethods = false)
  static class FakeReader {
    @Bean
    @Primary
    AwesomeListReader fakeAwesomeListReader() {
      return repo -> README.getOrDefault(repo, Optional.of(false));
    }
  }

  @Autowired JdbcClient jdbc;

  final List<UUID> created = new ArrayList<>();

  @AfterEach
  void clean() {
    created.forEach(id -> jdbc.sql("DELETE FROM resource WHERE id = :id").param("id", id).update());
    README.clear();
  }

  private UUID repo(String fullName, String type, boolean typeCurated) {
    UUID id = UUID.randomUUID();
    String url = "https://github.com/" + fullName;
    jdbc.sql(
            """
            INSERT INTO resource (id, canonical_url, url, title, description_origin, type, audience, data_origin,
                                  popularity, curated_fields)
            VALUES (:id, :url, :url, :title, 'SOURCE_PROVIDED', :type, 'BUILD_WITH_AI', 'INDEXED', 0.5,
                    CAST(:curated AS text[]))
            """)
        .param("id", id)
        .param("url", url)
        .param("title", fullName)
        .param("type", type)
        .param("curated", typeCurated ? "{type}" : "{}")
        .update();
    created.add(id);
    return id;
  }

  private String type(UUID id) {
    return jdbc.sql("SELECT type FROM resource WHERE id = :id")
        .param("id", id)
        .query(String.class)
        .single();
  }

  @Test
  void aRealListBecomesAnAwesomeListWithAnAuditEntryAndTheRestStayAsTheyAre() throws Exception {
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    UUID list = repo("someone/awesome-llm-" + suffix, "LIBRARY", false);
    UUID product = repo("acme/awesome-notes-" + suffix, "PRODUCT", false);
    UUID curated = repo("someone/awesome-curated-" + suffix, "MCP_SERVER", true);
    UUID offline = repo("someone/awesome-offline-" + suffix, "AGENT", false);
    README.put("someone/awesome-llm-" + suffix, Optional.of(true));
    README.put("acme/awesome-notes-" + suffix, Optional.of(false));
    README.put("someone/awesome-curated-" + suffix, Optional.of(true));
    README.put("someone/awesome-offline-" + suffix, Optional.empty());
    var admin = oauth2Login().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));

    mvc.perform(post("/api/v1/admin/ai-check/awesome-lists").with(admin).with(csrf().asHeader()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.set").value(1))
        .andExpect(jsonPath("$.keptCurated").value(1))
        .andExpect(jsonPath("$.unavailable").value(1));

    assertThat(type(list)).isEqualTo("AWESOME_LIST");
    assertThat(type(product)).isEqualTo("PRODUCT");
    assertThat(type(curated)).isEqualTo("MCP_SERVER");
    assertThat(type(offline)).isEqualTo("AGENT");
    assertThat(
            jdbc.sql(
                    "SELECT count(*) FROM admin_audit WHERE action = 'SET_FIELD' AND target_id = :t")
                .param("t", list.toString())
                .query(Integer.class)
                .single())
        .isEqualTo(1);
    assertThat(
            jdbc.sql(
                    """
                    SELECT before_value || ' -> ' || after_value FROM admin_audit
                     WHERE action = 'SET_FIELD' AND target_id = :t
                    """)
                .param("t", list.toString())
                .query(String.class)
                .single())
        .isEqualTo("LIBRARY -> AWESOME_LIST");

    mvc.perform(post("/api/v1/admin/ai-check/awesome-lists").with(admin).with(csrf().asHeader()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.set").value(0));
    assertThat(
            jdbc.sql(
                    "SELECT count(*) FROM admin_audit WHERE action = 'SET_FIELD' AND target_id = :t")
                .param("t", list.toString())
                .query(Integer.class)
                .single())
        .as("run again: the list is not read or audited twice")
        .isEqualTo(1);
  }

  @Test
  void onlyAnAdminWithTheCsrfTokenRunsIt() throws Exception {
    mvc.perform(post("/api/v1/admin/ai-check/awesome-lists").with(csrf().asHeader()))
        .andExpect(status().isUnauthorized());
  }
}
