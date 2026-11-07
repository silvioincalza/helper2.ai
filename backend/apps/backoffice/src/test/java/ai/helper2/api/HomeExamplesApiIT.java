package ai.helper2.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.helper2.catalog.ReviewQueue;
import ai.helper2.examples.DraftSource;
import ai.helper2.examples.DraftSources;
import ai.helper2.examples.ExampleDrafter;
import ai.helper2.examples.HomeExamples;
import ai.helper2.examples.ProposedDraft;
import ai.helper2.examples.config.ExamplesProperties;
import ai.helper2.examples.usecase.DraftHomeExamples;
import ai.helper2.examples.usecase.ListHomeExamples;
import ai.helper2.identity.AuditTrail;
import ai.helper2.shared.AudiencePath;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.OAuth2LoginRequestPostProcessor;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** The home examples (ADR 0015): the public pool, and the back office's add, change and remove. */
class HomeExamplesApiIT extends ApiTestBase {

  @Autowired JdbcClient jdbc;

  @Autowired ReviewQueue queue;

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
                    .with(csrf().asHeader())
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

  /**
   * The home page (the public app's, ADR 0032) gets only the examples that passed the check: read
   * through the same use case its endpoint calls. The endpoint's own answer is checked there
   * (HomeExamplesPoolApiIT).
   */
  @Test
  void theHomePageGetsOnlyExamplesThatPassedTheCheck() throws Exception {
    JsonNode shown = added("Shown " + tag, "translate documents " + tag);
    JsonNode waiting = added("Waiting " + tag, "translate letters " + tag);
    pass(shown.get("id").asString());

    List<String> served = served();
    assertThat(served)
        .contains(shown.get("id").asString())
        .doesNotContain(waiting.get("id").asString());
  }

  @Autowired ListHomeExamples listHomeExamples;

  List<String> served() {
    return listHomeExamples.served("en").stream().map(e -> e.getId().toString()).toList();
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
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content(input("added " + tag, "another query " + tag, List.of(deepl()))))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("duplicate"));

    mvc.perform(
            post("/api/v1/admin/home-examples")
                .with(admin)
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content(input("Web " + tag, "see www.example.com " + tag, List.of(deepl()))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("invalid-request"))
        .andExpect(jsonPath("$.detail").value("query: no web addresses."));

    mvc.perform(
            post("/api/v1/admin/home-examples")
                .with(admin)
                .with(csrf().asHeader())
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
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content(change.formatted(tag, tag, deepl(), 0)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.check.outcome").value("PENDING"))
        .andExpect(jsonPath("$.version").value(1));

    mvc.perform(
            put("/api/v1/admin/home-examples/" + id)
                .with(admin)
                .with(csrf().asHeader())
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
                .with(csrf().asHeader())
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
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    change("Gone " + tag, "translate a recipe " + tag, List.of(gone), "PAUSED", 0)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("PAUSED"))
        .andExpect(jsonPath("$.expected[0].title").doesNotExist());

    mvc.perform(
            put("/api/v1/admin/home-examples/" + id)
                .with(admin)
                .with(csrf().asHeader())
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
      mvc.perform(delete("/api/v1/admin/home-examples/" + id).with(admin).with(csrf().asHeader()))
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

  @Test
  void withoutAModelADraftRunIsUnavailableAndAudited() throws Exception {
    mvc.perform(post("/api/v1/admin/home-examples/drafts").with(admin).with(csrf().asHeader()))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("unavailable"));
    assertThat(audit("DRAFT_HOME_EXAMPLES")).containsExactly("NO_MODEL");
  }

  @Test
  void acceptingADraftAddsItAiDraftedAndThePoolMarksIt() throws Exception {
    UUID item =
        queue.open(
            ReviewQueue.Kind.EXAMPLE_CANDIDATE,
            deepl(),
            "Drafted example: Drafted " + tag,
            """
            {"label":"Drafted %s","query":"translate a contract %s","path":"USE_AI","model":"nemotron-test"}
            """
                .formatted(tag, tag),
            ReviewQueue.Links.NONE);
    mvc.perform(
            put("/api/v1/admin/review-items/" + item + "/resolution")
                .with(admin)
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"ACCEPTED\"}"))
        .andExpect(status().isOk());
    String id =
        jdbc.sql("SELECT id FROM home_example WHERE label = :l AND origin = 'AI_DRAFTED'")
            .param("l", "Drafted " + tag)
            .query(UUID.class)
            .single()
            .toString();
    pass(id);

    for (var e : listHomeExamples.served("en")) {
      assertThat(e.getOrigin() == ai.helper2.examples.ExampleOrigin.AI_DRAFTED)
          .isEqualTo(e.getId().toString().equals(id));
      if (e.getId().toString().equals(id)) {
        assertThat(e.getModel()).isEqualTo("nemotron-test");
      }
    }
  }

  @Autowired HomeExamples examples;
  @Autowired AuditTrail auditTrail;
  @Autowired ObjectMapper objectMapper;
  @Autowired TransactionTemplate transaction;
  @Autowired ExamplesProperties examplesProperties;

  @Test
  void aSecondDraftRunSkipsTheResourcesAndTextsOfDraftsStillWaiting() {
    UUID other =
        jdbc.sql("SELECT id FROM resource WHERE id <> :d ORDER BY id LIMIT 1")
            .param("d", deepl())
            .query(UUID.class)
            .single();
    queue.open(
        ReviewQueue.Kind.EXAMPLE_CANDIDATE,
        deepl(),
        "Drafted example: Waiting " + tag,
        """
        {"label":"Waiting %s","query":"translate a lease %s","path":"USE_AI","model":"nemotron-test"}
        """
            .formatted(tag, tag),
        ReviewQueue.Links.NONE);
    Set<UUID> excluded = new HashSet<>();
    DraftSources sources =
        (limit, excluding) -> {
          excluded.addAll(excluding);
          return List.of(new DraftSource(other, "Other", "PRODUCT", "USE_AI", null));
        };
    ExampleDrafter drafter =
        new ExampleDrafter() {
          @Override
          public String getModel() {
            return "nemotron-test";
          }

          @Override
          public List<ProposedDraft> draft(List<DraftSource> given) {
            return List.of(
                new ProposedDraft(
                    other, "waiting " + tag, "a new query " + tag, AudiencePath.USE_AI));
          }
        };
    var run =
        new DraftHomeExamples(
            examples,
            sources,
            drafter,
            queue,
            auditTrail,
            objectMapper,
            transaction,
            examplesProperties);

    assertThat(run.draft(adminId).count()).as("the label is a waiting draft's").isZero();
    assertThat(excluded).as("the waiting draft's resource is not given again").contains(deepl());
  }

  @Test
  void aDraftThatRepeatsAnExampleStaysOpen() throws Exception {
    added("Repeated " + tag, "translate a poem " + tag);
    UUID item =
        queue.open(
            ReviewQueue.Kind.EXAMPLE_CANDIDATE,
            deepl(),
            "Drafted example: repeated " + tag,
            """
            {"label":"repeated %s","query":"another poem %s","path":"USE_AI","model":"nemotron-test"}
            """
                .formatted(tag, tag),
            ReviewQueue.Links.NONE);
    mvc.perform(
            put("/api/v1/admin/review-items/" + item + "/resolution")
                .with(admin)
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"ACCEPTED\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("duplicate"));
    assertThat(queue.find(item).orElseThrow().status()).isEqualTo(ReviewQueue.Status.OPEN);
    assertThat(audit("ADD_HOME_EXAMPLE")).contains("DUPLICATE");
  }

  @Test
  void aDraftThatCannotBeReadIsRefusedNotAServerError() throws Exception {
    UUID item =
        queue.open(
            ReviewQueue.Kind.EXAMPLE_CANDIDATE,
            deepl(),
            "Drafted example: broken " + tag,
            "{\"label\":\"Broken\"}",
            ReviewQueue.Links.NONE);
    mvc.perform(
            put("/api/v1/admin/review-items/" + item + "/resolution")
                .with(admin)
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"ACCEPTED\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail").value("The draft cannot be read."));
    assertThat(queue.find(item).orElseThrow().status()).isEqualTo(ReviewQueue.Status.OPEN);
  }
}
