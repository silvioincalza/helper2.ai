package ai.helper2.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.helper2.catalog.ReviewQueue;
import ai.helper2.shared.CanonicalUrl;
import ai.helper2.shared.ResourceId;
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
 * PUT /admin/review-items/{id}/candidate (ADR 0065): an admin fixes a resource candidate's title,
 * type and tags; accepting it indexes what the admin wrote, with a type its finder does not
 * declare.
 */
class CandidateEditApiIT extends ApiTestBase {

  @Autowired JdbcClient jdbc;
  @Autowired ReviewQueue queue;

  final String url =
      CanonicalUrl.of("https://www.engadget.com/candidate-edit-it-gemini-carbon").getValue();
  final String adminId = "edits-" + UUID.randomUUID();
  final OAuth2LoginRequestPostProcessor admin =
      oauth2Login()
          .attributes(a -> a.put("sub", adminId))
          .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
  UUID item;

  /** The candidate as proposed: what the admin sees, and sends back as expectedDetails. */
  String details;

  @BeforeEach
  void propose() {
    details =
        "{\"version\":2,\"sourceId\":\"discovery-self-hosted\",\"url\":\""
            + url
            + "\",\"title\":\"Google Gemini Carbon promises a leap | Engadget\",\"type\":\"PRODUCT\","
            + "\"tags\":[\"self-hosted\"],\"snippet\":\"Google has barged in with Gemini 4 Carbon.\","
            + "\"foundBy\":[{\"sourceId\":\"discovery-self-hosted\"}]}";
    assertThat(
            queue.proposeCandidate(
                ReviewQueue.Kind.RESOURCE_CANDIDATE, url, "New PRODUCT", details))
        .isTrue();
    item =
        jdbc.sql("SELECT id FROM review_item WHERE candidate_url = :u")
            .param("u", url)
            .query(UUID.class)
            .single();
  }

  @AfterEach
  void clean() {
    jdbc.sql("DELETE FROM review_item WHERE candidate_url = :u").param("u", url).update();
    jdbc.sql("DELETE FROM resource WHERE canonical_url = :u").param("u", url).update();
  }

  /** An edit of [fields] (a JSON object) of the details the admin saw. */
  ResultActions edit(UUID id, String fields) throws Exception {
    return edit(id, details, fields);
  }

  ResultActions edit(UUID id, String seen, String fields) throws Exception {
    String expected =
        "{\"expectedDetails\":\"" + seen.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    String body = fields.equals("{}") ? expected + "}" : expected + "," + fields.substring(1);
    return mvc.perform(
        put("/api/v1/admin/review-items/" + id + "/candidate")
            .with(admin)
            .with(csrf().asHeader())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
  }

  List<String> audit() {
    return jdbc.sql(
            "SELECT action || ' ' || outcome FROM admin_audit WHERE admin_id = :a ORDER BY at, id")
        .param("a", adminId)
        .query(String.class)
        .list();
  }

  @Test
  void anEditedCandidateIsAcceptedAsTheAdminWroteIt() throws Exception {
    String body =
        edit(
                item,
                "{\"title\":\"Google Gemini Carbon promises a leap\",\"type\":\"NEWS\","
                    + "\"tags\":[\"coding\"]}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("OPEN"))
            .andExpect(
                jsonPath("$.reason")
                    .value(
                        "New NEWS (coding) found by discovery-self-hosted:"
                            + " Google Gemini Carbon promises a leap"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    ContractValidator.assertMatches("ReviewItem", body);

    mvc.perform(
            put("/api/v1/admin/review-items/" + item + "/resolution")
                .with(admin)
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"ACCEPTED\"}"))
        .andExpect(status().isOk());

    String resource = ResourceId.of(CanonicalUrl.of(url)).toString();
    mvc.perform(get("/api/v1/resources/" + resource))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.card.title").value("Google Gemini Carbon promises a leap"))
        .andExpect(jsonPath("$.card.type").value("NEWS"))
        .andExpect(jsonPath("$.card.tags[0]").value("coding"));
    assertThat(
            jdbc.sql(
                    "SELECT CAST(extra -> 'edited' AS text) FROM source_record WHERE canonical_url = :u")
                .param("u", url)
                .query(String.class)
                .single())
        .isEqualTo("[\"tags\", \"title\", \"type\"]");
    assertThat(audit())
        .containsExactly("EDIT_CANDIDATE EDITED title, type, tags", "RESOLVE_REVIEW_ITEM ACCEPTED");
  }

  @Test
  void aRefusedEditChangesNothing() throws Exception {
    String before =
        jdbc.sql("SELECT details FROM review_item WHERE id = :id")
            .param("id", item)
            .query(String.class)
            .single();
    edit(item, "{}").andExpect(status().isBadRequest());
    edit(item, "{\"title\":\"  \"}").andExpect(status().isBadRequest());
    edit(item, "{\"type\":\"COURSE\"}").andExpect(status().isBadRequest());
    edit(item, "{\"tags\":[\"Not A Tag\"]}").andExpect(status().isBadRequest());
    edit(UUID.randomUUID(), "{\"title\":\"x\"}").andExpect(status().isNotFound());

    assertThat(
            jdbc.sql("SELECT details FROM review_item WHERE id = :id")
                .param("id", item)
                .query(String.class)
                .single())
        .isEqualTo(before);
    assertThat(audit()).isEmpty();
  }

  @Test
  void anEditOfDetailsThatChangedSinceTheyWereShownIsRefused() throws Exception {
    edit(item, details.replace("Engadget", "The Verge"), "{\"title\":\"x\"}")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("version-conflict"));
    assertThat(
            jdbc.sql("SELECT details FROM review_item WHERE id = :id")
                .param("id", item)
                .query(String.class)
                .single())
        .isEqualTo(details);
    assertThat(audit()).isEmpty();
  }

  @Test
  void aDecidedItemOrAnotherKindIsNotEdited() throws Exception {
    jdbc.sql(
            "UPDATE review_item SET status = 'REJECTED', resolved_at = now(), resolved_by = 'x'"
                + " WHERE id = :id")
        .param("id", item)
        .update();
    edit(item, "{\"title\":\"x\"}")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("already-decided"));
  }

  @Test
  void onlyAdminsEditAndOnlyWithTheCsrfToken() throws Exception {
    String path = "/api/v1/admin/review-items/" + item + "/candidate";
    String body = "{\"title\":\"x\"}";
    mvc.perform(
            put(path).with(csrf().asHeader()).contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isUnauthorized());
    mvc.perform(
            put(path)
                .with(oauth2Login())
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isForbidden());
    mvc.perform(put(path).with(admin).contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isForbidden());
  }
}
