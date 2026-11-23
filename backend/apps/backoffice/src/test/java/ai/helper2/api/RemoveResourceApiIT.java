package ai.helper2.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.helper2.shared.CanonicalUrl;
import ai.helper2.shared.ResourceId;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.OAuth2LoginRequestPostProcessor;
import org.springframework.test.web.servlet.ResultActions;

class RemoveResourceApiIT extends ApiTestBase {

  @Autowired JdbcClient jdbc;

  final String adminId = "remove-" + UUID.randomUUID();
  final OAuth2LoginRequestPostProcessor admin =
      oauth2Login()
          .attributes(a -> a.put("sub", adminId))
          .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
  final String url = "https://github.com/someone/awesome-crypto-" + UUID.randomUUID();
  final UUID id = ResourceId.of(CanonicalUrl.of(url)).getValue();

  @BeforeEach
  void insert() {
    jdbc.sql(
            """
            INSERT INTO resource (id, canonical_url, url, title, description, description_origin, type, audience,
                                  data_origin)
            VALUES (:id, :url, :url, 'awesome-crypto', 'Crypto sources', 'SOURCE_PROVIDED', 'AWESOME_LIST',
                    'BUILD_WITH_AI', 'INDEXED')
            """)
        .param("id", id)
        .param("url", url)
        .update();
  }

  @AfterEach
  void clean() {
    jdbc.sql("DELETE FROM resource WHERE id = :id").param("id", id).update();
    jdbc.sql("DELETE FROM removed_resource WHERE resource_key = :id").param("id", id).update();
    jdbc.sql("DELETE FROM candidate_decision WHERE candidate_key = :id").param("id", id).update();
  }

  ResultActions remove(UUID resource, String body) throws Exception {
    return mvc.perform(
        post("/api/v1/admin/ai-check/" + resource + "/remove")
            .with(admin)
            .with(csrf().asHeader())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
  }

  boolean listed() {
    return jdbc.sql("SELECT EXISTS (SELECT 1 FROM resource WHERE id = :id)")
        .param("id", id)
        .query(Boolean.class)
        .single();
  }

  @Test
  void anAdminRemovesAResourceWithAReasonAndTheAuditKeepsItsAddress() throws Exception {
    mvc.perform(
            post("/api/v1/admin/ai-check/" + id + "/remove")
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"not about AI\"}"))
        .andExpect(status().isUnauthorized());
    remove(id, "{\"reason\":\"   \"}").andExpect(status().isBadRequest());
    remove(id, "{}").andExpect(status().isBadRequest());
    assertThat(listed()).isTrue();

    remove(id, "{\"reason\":\"A crypto list, not about AI\"}").andExpect(status().isNoContent());

    assertThat(listed()).isFalse();
    assertThat(
            jdbc.sql(
                    """
                    SELECT action || ' ' || target_id || ' ' || outcome || ' | ' || before_value || ' | ' || reason
                      FROM admin_audit WHERE admin_id = :a
                    """)
                .param("a", adminId)
                .query(String.class)
                .list())
        .containsExactly(
            "REMOVE_RESOURCE " + id + " REMOVED | " + url + " | A crypto list, not about AI");
    remove(id, "{\"reason\":\"again\"}").andExpect(status().isNotFound());
  }
}
