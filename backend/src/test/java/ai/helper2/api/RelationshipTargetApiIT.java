package ai.helper2.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.helper2.catalog.ReviewEvidenceFixture;
import java.util.List;
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

/**
 * PUT /admin/review-items/{id}/relationship-target (ADR 0064): a relationship candidate is pointed
 * at a catalog resource, at a resource added for it, or renamed; a refused change adds nothing.
 */
class RelationshipTargetApiIT extends ApiTestBase {

  static final String NEW_URL = "https://www.langchain-api-it.example/";
  static final String NEW_CANONICAL = "https://langchain-api-it.example";

  @Autowired JdbcClient jdbc;

  ReviewEvidenceFixture fixture;
  final UUID other = UUID.randomUUID();
  final String adminId = "targets-" + UUID.randomUUID();
  final OAuth2LoginRequestPostProcessor admin =
      oauth2Login()
          .attributes(a -> a.put("sub", adminId))
          .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));

  @BeforeEach
  void insert() {
    fixture = new ReviewEvidenceFixture(jdbc).insert();
    jdbc.sql(
            """
            INSERT INTO resource (id, canonical_url, url, title, description_origin, type, audience, data_origin)
            VALUES (:id, :url, :url, 'LangChain framework', 'SOURCE_PROVIDED', 'LIBRARY', 'BUILD_WITH_AI', 'INDEXED')
            """)
        .param("id", other)
        .param("url", "https://other-api-it.example/" + other)
        .update();
  }

  @AfterEach
  void clean() {
    fixture.delete();
    jdbc.sql("DELETE FROM resource WHERE id = :id OR canonical_url = :url")
        .param("id", other)
        .param("url", NEW_CANONICAL)
        .update();
  }

  ResultActions change(UUID item, String body) throws Exception {
    return mvc.perform(
        put("/api/v1/admin/review-items/" + item + "/relationship-target")
            .with(admin)
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
  }

  /** The column as text; null when the column is NULL ({@code single()} refuses a null value). */
  String relationship(String column) {
    List<String> values =
        jdbc.sql(
                "SELECT CAST(" + column + " AS text) FROM relationship WHERE from_resource_id = :r")
            .param("r", fixture.resource)
            .query((rs, i) -> rs.getString(1))
            .list();
    assertThat(values).hasSize(1);
    return values.getFirst();
  }

  List<String> audit() {
    return jdbc.sql(
            "SELECT action || ' ' || outcome FROM admin_audit WHERE admin_id = :a ORDER BY at, id")
        .param("a", adminId)
        .query(String.class)
        .list();
  }

  @Test
  void aCandidatePointedAtACatalogResourceIsCuratedAndVerifiedWhenAccepted() throws Exception {
    String body =
        change(fixture.relationshipItem, "{\"resourceId\":\"" + other + "\"}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.relationship.targetId").value(other.toString()))
            .andExpect(jsonPath("$.relationship.target").value("LangChain framework"))
            .andExpect(jsonPath("$.relationship.origin").value("CURATED"))
            .andExpect(jsonPath("$.item.status").value("OPEN"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    ContractValidator.assertMatches("ReviewEvidence", body);
    // The kind is still the model's; the target is the admin's.
    assertThat(body).contains("\"relationship.kind\"").doesNotContain("\"relationship.target\"");

    mvc.perform(
            put("/api/v1/admin/review-items/" + fixture.relationshipItem + "/resolution")
                .with(admin)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"ACCEPTED\"}"))
        .andExpect(status().isOk());

    assertThat(relationship("verification")).isEqualTo("VERIFIED");
    assertThat(relationship("to_resource_id")).isEqualTo(other.toString());
    assertThat(audit())
        .containsExactly(
            "CHANGE_RELATIONSHIP_TARGET LINKED " + other, "RESOLVE_REVIEW_ITEM ACCEPTED");
  }

  @Test
  void aResourceIsAddedOnceAndTheSameAddressGivesItAgain() throws Exception {
    String add =
        "{\"newResource\":{\"title\":\" LangChain \",\"url\":\""
            + NEW_URL
            + "\",\"type\":\"LIBRARY\"}}";
    change(fixture.relationshipItem, add)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.relationship.target").value("LangChain"));
    UUID added =
        jdbc.sql("SELECT id FROM resource WHERE canonical_url = :url")
            .param("url", NEW_CANONICAL)
            .query(UUID.class)
            .single();
    assertThat(
            jdbc.sql(
                    """
                    SELECT r.data_origin || ' ' || r.type || ' ' || r.enrichment_status || ' ' || s.source_id
                    FROM resource r JOIN source_record s ON s.resource_id = r.id WHERE r.id = :id
                    """)
                .param("id", added)
                .query(String.class)
                .single())
        .isEqualTo("CURATED LIBRARY NOT_RUN curated");

    change(fixture.relationshipItem, "{\"title\":\"LangChain (old)\"}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.relationship.targetId").doesNotExist());
    change(fixture.relationshipItem, add).andExpect(status().isOk());

    assertThat(relationship("to_resource_id")).isEqualTo(added.toString());
    assertThat(audit())
        .containsExactly(
            "ADD_RESOURCE ADDED",
            "CHANGE_RELATIONSHIP_TARGET LINKED " + added,
            "CHANGE_RELATIONSHIP_TARGET RENAMED",
            "CHANGE_RELATIONSHIP_TARGET LINKED " + added);
  }

  @Test
  void aRefusedChangeChangesAndAddsNothing() throws Exception {
    String add =
        "{\"newResource\":{\"title\":\"LangChain\",\"url\":\""
            + NEW_URL
            + "\",\"type\":\"LIBRARY\"}}";
    change(fixture.relationshipItem, "{\"resourceId\":\"" + other + "\",\"title\":\"x\"}")
        .andExpect(status().isBadRequest());
    change(fixture.relationshipItem, "{}").andExpect(status().isBadRequest());
    change(
            fixture.relationshipItem,
            "{\"newResource\":{\"title\":\"LangChain\",\"url\":\"http://langchain.example\","
                + "\"type\":\"LIBRARY\"}}")
        .andExpect(status().isBadRequest());
    change(
            fixture.relationshipItem,
            "{\"newResource\":{\"title\":\"LangChain\",\"url\":\""
                + NEW_URL
                + "\",\"type\":\"NEWS\"}}")
        .andExpect(status().isBadRequest());
    change(fixture.relationshipItem, "{\"resourceId\":\"" + UUID.randomUUID() + "\"}")
        .andExpect(status().isBadRequest());
    change(fixture.relationshipItem, "{\"resourceId\":\"" + fixture.resource + "\"}")
        .andExpect(status().isBadRequest());
    change(fixture.validationItem, add)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("not-applicable"));
    change(UUID.randomUUID(), "{\"title\":\"LangChain\"}").andExpect(status().isNotFound());

    assertThat(
            jdbc.sql("SELECT count(*) FROM resource WHERE canonical_url = :url")
                .param("url", NEW_CANONICAL)
                .query(Long.class)
                .single())
        .isZero();
    assertThat(relationship("to_title")).isEqualTo("LangChain");
    assertThat(relationship("origin")).isEqualTo("SELF_DECLARED");
    assertThat(audit()).isEmpty();
  }

  @Test
  void aDecidedItemKeepsItsTarget() throws Exception {
    jdbc.sql(
            "UPDATE review_item SET status = 'REJECTED', resolved_at = now(), resolved_by = 'x' WHERE id = :id")
        .param("id", fixture.relationshipItem)
        .update();
    change(fixture.relationshipItem, "{\"resourceId\":\"" + other + "\"}")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("already-decided"));
    assertThat(relationship("to_resource_id")).isNull();
  }

  @Test
  void onlyAdminsChangeATargetAndOnlyWithTheCsrfToken() throws Exception {
    String url = "/api/v1/admin/review-items/" + fixture.relationshipItem + "/relationship-target";
    String body = "{\"title\":\"LangChain\"}";
    mvc.perform(put(url).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isUnauthorized());
    mvc.perform(
            put(url)
                .with(oauth2Login())
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isForbidden());
    mvc.perform(put(url).with(admin).contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isForbidden());
  }
}
