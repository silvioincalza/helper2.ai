package ai.helper2.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.OAuth2LoginRequestPostProcessor;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The home examples (ADR 0049): the public pool, and the back office's add, change and remove. */
class HomeExamplesApiIT extends ApiTestBase {

  @Autowired JdbcClient jdbc;

  final JsonMapper json = JsonMapper.builder().build();
  final String adminId = "examples-" + UUID.randomUUID();
  final OAuth2LoginRequestPostProcessor admin =
      oauth2Login()
          .attributes(a -> a.put("sub", adminId))
          .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
  final String tag = UUID.randomUUID().toString().substring(0, 6);

  /** A resource of the curated seed: always in the catalog. */
  UUID deepl() {
    return jdbc.sql("SELECT id FROM resource WHERE canonical_url = 'https://deepl.com/translator'")
        .query(UUID.class)
        .single();
  }

  String input(String label, String query, List<UUID> expected) {
    return """
    {"label":"%s","query":"%s","path":"USE_AI","expectedResourceIds":[%s]}
    """
        .formatted(
            label, query, String.join(",", expected.stream().map(id -> "\"" + id + "\"").toList()));
  }

  JsonNode added(String label, String query) throws Exception {
    MvcResult r =
        mvc.perform(
                post("/api/v1/admin/home-examples")
                    .with(admin)
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(input(label, query, List.of(deepl()))))
            .andExpect(status().isCreated())
            .andReturn();
    ContractValidator.assertMatches("AdminHomeExample", r.getResponse().getContentAsString());
    return json.readTree(r.getResponse().getContentAsString());
  }

  List<String> audit(String action) {
    return jdbc.sql("SELECT outcome FROM admin_audit WHERE admin_id = :a AND action = :action")
        .param("a", adminId)
        .param("action", action)
        .query(String.class)
        .list();
  }

  void pass(String id) {
    jdbc.sql("UPDATE home_example SET check_outcome = 'PASS', check_best_rank = 1 WHERE id = :id")
        .param("id", UUID.fromString(id))
        .update();
  }

  @Test
  void theHomePageGetsOnlyExamplesThatPassedTheCheckCachedForEveryone() throws Exception {
    JsonNode shown = added("Shown " + tag, "translate documents " + tag);
    JsonNode waiting = added("Waiting " + tag, "translate letters " + tag);
    pass(shown.get("id").asString());

    MvcResult r =
        mvc.perform(get("/api/v1/home/examples"))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "max-age=300, public"))
            .andReturn();
    String body = r.getResponse().getContentAsString();
    ContractValidator.assertMatches("HomeExamplePool", body);
    assertThat(body)
        .contains(shown.get("id").asString())
        .doesNotContain(waiting.get("id").asString())
        .doesNotContain("expected")
        .doesNotContain("check");
  }

  @Test
  void addingChecksTheTextsAndTheExpectedResultsAndRecordsEveryAttempt() throws Exception {
    JsonNode example = added("Added " + tag, "translate a menu " + tag);
    assertThat(example.get("check").get("outcome").asString()).isEqualTo("PENDING");
    assertThat(example.get("origin").asString()).isEqualTo("CURATED");
    assertThat(example.get("expected").get(0).get("title").asString())
        .isEqualTo("DeepL Translator");

    mvc.perform(
            post("/api/v1/admin/home-examples")
                .with(admin)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(input("added " + tag, "another query " + tag, List.of(deepl()))))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("duplicate"));

    mvc.perform(
            post("/api/v1/admin/home-examples")
                .with(admin)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(input("Web " + tag, "see www.example.com " + tag, List.of(deepl()))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("invalid-request"))
        .andExpect(jsonPath("$.detail").value("query: no web addresses."));

    mvc.perform(
            post("/api/v1/admin/home-examples")
                .with(admin)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    input("Unknown " + tag, "unknown result " + tag, List.of(UUID.randomUUID()))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("invalid-request"));

    assertThat(audit("ADD_HOME_EXAMPLE"))
        .containsExactlyInAnyOrder("ADDED", "DUPLICATE", "INVALID", "INVALID");
  }

  @Test
  void aChangeOnAnOldVersionIsAConflictAndANewQueryWaitsForTheCheckAgain() throws Exception {
    JsonNode example = added("Changed " + tag, "translate a book " + tag);
    String id = example.get("id").asString();
    pass(id);
    String change =
        """
        {"label":"Changed %s","query":"translate a novel %s","path":"USE_AI","expectedResourceIds":["%s"],
         "status":"ACTIVE","version":%d}
        """;

    mvc.perform(
            put("/api/v1/admin/home-examples/" + id)
                .with(admin)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(change.formatted(tag, tag, deepl(), 0)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.check.outcome").value("PENDING"))
        .andExpect(jsonPath("$.version").value(1));

    mvc.perform(
            put("/api/v1/admin/home-examples/" + id)
                .with(admin)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(change.formatted(tag, tag, deepl(), 0)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("version-conflict"));

    assertThat(audit("CHANGE_HOME_EXAMPLE")).containsExactlyInAnyOrder("CHANGED", "CONFLICT");
  }

  String change(String label, String query, List<UUID> expected, String status, int version) {
    return """
    {"label":"%s","query":"%s","path":"USE_AI","expectedResourceIds":[%s],"status":"%s","version":%d}
    """
        .formatted(
            label,
            query,
            String.join(",", expected.stream().map(id -> "\"" + id + "\"").toList()),
            status,
            version);
  }

  @Test
  void aChangeToAnotherExamplesLabelIsADuplicateAndTheAttemptIsStillRecorded() throws Exception {
    added("Taken " + tag, "translate a poem " + tag);
    String id = added("Other " + tag, "translate a song " + tag).get("id").asString();

    mvc.perform(
            put("/api/v1/admin/home-examples/" + id)
                .with(admin)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    change(
                        "taken " + tag, "translate a song " + tag, List.of(deepl()), "ACTIVE", 0)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("duplicate"));

    assertThat(audit("CHANGE_HOME_EXAMPLE")).containsExactly("DUPLICATE");
  }

  @Test
  void anExpectedResultThatLeftTheCatalogDoesNotBlockAPauseButANewOneMustBeKnown()
      throws Exception {
    String id = added("Gone " + tag, "translate a recipe " + tag).get("id").asString();
    UUID gone = UUID.randomUUID();
    jdbc.sql("UPDATE home_example SET expected_resource_ids = ARRAY[:gone]::uuid[] WHERE id = :id")
        .param("gone", gone)
        .param("id", UUID.fromString(id))
        .update();

    mvc.perform(
            put("/api/v1/admin/home-examples/" + id)
                .with(admin)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    change("Gone " + tag, "translate a recipe " + tag, List.of(gone), "PAUSED", 0)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("PAUSED"))
        .andExpect(jsonPath("$.expected[0].title").doesNotExist());

    mvc.perform(
            put("/api/v1/admin/home-examples/" + id)
                .with(admin)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    change(
                        "Gone " + tag,
                        "translate a recipe " + tag,
                        List.of(gone, UUID.randomUUID()),
                        "PAUSED",
                        1)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("invalid-request"));

    assertThat(audit("CHANGE_HOME_EXAMPLE")).containsExactlyInAnyOrder("PAUSED", "INVALID");
  }

  @Test
  void removingIsRecordedAndRemovingAgainIsNotAnError() throws Exception {
    String id = added("Removed " + tag, "translate a sign " + tag).get("id").asString();
    for (int i = 0; i < 2; i++) {
      mvc.perform(delete("/api/v1/admin/home-examples/" + id).with(admin).with(csrf()))
          .andExpect(status().isNoContent());
    }
    assertThat(audit("REMOVE_HOME_EXAMPLE")).containsExactlyInAnyOrder("REMOVED", "NOT_FOUND");
  }

  @Test
  void onlyAnAdministratorSeesTheBackOfficeList() throws Exception {
    mvc.perform(get("/api/v1/admin/home-examples")).andExpect(status().isUnauthorized());
    mvc.perform(
            get("/api/v1/admin/home-examples")
                .with(oauth2Login().attributes(a -> a.put("sub", "visitor-" + tag))))
        .andExpect(status().isForbidden());
    mvc.perform(get("/api/v1/admin/home-examples").with(admin)).andExpect(status().isOk());
  }
}
