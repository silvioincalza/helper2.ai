package ai.searchavo.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.searchavo.catalog.ReviewDecisions;
import com.jayway.jsonpath.JsonPath;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.OAuth2LoginRequestPostProcessor;

class RelationshipTargetGroupUndoApiIT extends ApiTestBase {

  @TestConfiguration
  static class EveryItemWasDecidedMeanwhile {

    @Bean
    @Primary
    ReviewDecisions decidedMeanwhile() {
      return (itemId, decision, by, note) -> false;
    }
  }

  @Autowired JdbcClient jdbc;

  final String name = "Zqundo" + UUID.randomUUID().toString().substring(0, 8);
  final String key = name.toLowerCase();
  final String adminId = "group-undo-" + UUID.randomUUID();
  final OAuth2LoginRequestPostProcessor admin =
      oauth2Login()
          .attributes(a -> a.put("sub", adminId))
          .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
  final List<UUID> resources = new ArrayList<>();
  final List<UUID> relationships = new ArrayList<>();
  UUID target;

  @BeforeEach
  void insert() {
    for (int i = 0; i < 3; i++) {
      relationships.add(candidate(resource("Uses " + name + " " + i)));
    }
    target = resource(name + " app");
  }

  @AfterEach
  void clean() {
    resources.forEach(
        id -> jdbc.sql("DELETE FROM resource WHERE id = :id").param("id", id).update());
  }

  private UUID resource(String title) {
    UUID id = UUID.randomUUID();
    jdbc.sql(
            """
            INSERT INTO resource (id, canonical_url, url, title, description_origin, type, audience, data_origin)
            VALUES (:id, :url, :url, :title, 'SOURCE_PROVIDED', 'LIBRARY', 'BUILD_WITH_AI', 'INDEXED')
            """)
        .param("id", id)
        .param("url", "https://relationship-group-undo.example/" + id)
        .param("title", title)
        .update();
    resources.add(id);
    return id;
  }

  private UUID candidate(UUID from) {
    UUID relationship = UUID.randomUUID();
    jdbc.sql(
            """
            INSERT INTO relationship (id, from_resource_id, to_title, kind, verification, origin)
            VALUES (:id, :from, :title, 'INTEGRATES_WITH', 'UNVERIFIED', 'AI_EXTRACTED')
            """)
        .param("id", relationship)
        .param("from", from)
        .param("title", name)
        .update();
    jdbc.sql(
            """
            INSERT INTO review_item (id, kind, status, resource_id, relationship_id, reason)
            VALUES (:id, 'RELATIONSHIP_CANDIDATE', 'OPEN', :from, :rel, 'Unverified relationship')
            """)
        .param("id", UUID.randomUUID())
        .param("from", from)
        .param("rel", relationship)
        .update();
    return relationship;
  }

  private String decide(String decision) throws Exception {
    return mvc.perform(
            post("/api/v1/admin/relationship-targets/decision")
                .with(admin)
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"key\":\"" + key + "\"," + decision + "}"))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  private List<Map<String, Object>> states() {
    return relationships.stream()
        .map(id ->
            jdbc.sql(
                    """
                    SELECT rel.to_resource_id AS target, rel.verification, i.status
                    FROM relationship rel JOIN review_item i ON i.relationship_id = rel.id
                    WHERE rel.id = :id
                    """)
                .param("id", id)
                .query()
                .singleRow())
        .toList();
  }

  private List<String> audit() {
    return jdbc.sql(
            "SELECT action || ' ' || outcome FROM admin_audit WHERE admin_id = :a ORDER BY at, id")
        .param("a", adminId)
        .query(String.class)
        .list();
  }

  private void assertNothingChanged() {
    assertThat(states())
        .allSatisfy(s ->
            assertThat(s)
                .containsEntry("target", null)
                .containsEntry("verification", "UNVERIFIED")
                .containsEntry("status", "OPEN"));
  }

  @Test
  void aLinkOfAnItemDecidedMeanwhileIsUndoneWithItsAuditLines() throws Exception {
    String body = decide("\"decision\":\"LINK\",\"resourceId\":\"" + target + "\"");

    assertThat(JsonPath.<Integer>read(body, "$.decided")).isZero();
    assertThat(JsonPath.<Integer>read(body, "$.skipped")).isEqualTo(3);
    assertThat(JsonPath.<Integer>read(body, "$.left")).isEqualTo(3);
    assertNothingChanged();
    assertThat(audit()).isEmpty();
  }

  @Test
  void aRejectionOfAnItemDecidedMeanwhileIsSkippedAndKeepsItsAuditLine() throws Exception {
    String body = decide("\"decision\":\"REJECT\"");
    String batch = JsonPath.read(body, "$.batch");

    assertThat(JsonPath.<Integer>read(body, "$.decided")).isZero();
    assertThat(JsonPath.<Integer>read(body, "$.skipped")).isEqualTo(3);
    assertNothingChanged();
    assertThat(audit()).containsOnly("RESOLVE_REVIEW_ITEM NOT_OPEN bulk:" + batch).hasSize(3);
  }
}
