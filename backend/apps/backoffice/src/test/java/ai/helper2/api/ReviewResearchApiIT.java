package ai.helper2.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.helper2.research.ResearchRun;
import ai.helper2.research.ResearchRuns;
import ai.helper2.research.ResearchStep;
import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.OAuth2LoginRequestPostProcessor;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(
    properties = {"helper2.research.enabled=true", "helper2.research.concurrency=0"})
class ReviewResearchApiIT extends ApiTestBase {

  @Autowired JdbcClient jdbc;
  @Autowired ResearchRuns runs;

  final OAuth2LoginRequestPostProcessor admin =
      oauth2Login()
          .attributes(a -> a.put("sub", "research-" + UUID.randomUUID()))
          .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
  final List<UUID> items = new ArrayList<>();

  @AfterEach
  void clean() {
    items.forEach(
        id -> jdbc.sql("DELETE FROM review_item WHERE id = :id").param("id", id).update());
  }

  private UUID item(String kind, String status) {
    UUID id = UUID.randomUUID();
    String url = "https://research.example/" + id;
    boolean json = kind.equals("RESOURCE_CANDIDATE");
    String details =
        "{\"url\":\""
            + url
            + "\",\"title\":\"Acme | AI\",\"type\":\"PRODUCT\",\"tags\":[\"meetings\"]}";
    jdbc.sql(
            """
            INSERT INTO review_item (id, kind, status, candidate_url, reason, details, note, resolved_at)
            VALUES (:id, :kind, :status, :url, 'New PRODUCT found by discovery-x: Acme', CAST(:details AS jsonb),
                    :note, CASE WHEN :status = 'OPEN' THEN NULL ELSE now() END)
            """)
        .param("id", id)
        .param("kind", kind)
        .param("status", status)
        .param("url", url)
        .param("details", json ? details : null)
        .param("note", json ? null : "URL: " + url)
        .update();
    items.add(id);
    return id;
  }

  private String start(String body) throws Exception {
    return mvc.perform(
            post("/api/v1/admin/review-research")
                .with(admin)
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isAccepted())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  @Test
  void onlyOpenResourceAndCourseCandidatesAreQueuedAndOnlyOncePerItem() throws Exception {
    UUID resource = item("RESOURCE_CANDIDATE", "OPEN");
    UUID course = item("COURSE_CANDIDATE", "OPEN");
    UUID decided = item("RESOURCE_CANDIDATE", "REJECTED");
    UUID report = item("USER_REPORT", "OPEN");

    String body =
        start(
            "{\"itemIds\":[\"%s\",\"%s\",\"%s\",\"%s\"]}"
                .formatted(resource, course, decided, report));
    List<String> queued = JsonPath.read(body, "$.runs[*].itemId");
    assertThat(queued).containsExactlyInAnyOrder(resource.toString(), course.toString());

    String again = start("{\"itemIds\":[\"%s\"]}".formatted(resource));
    assertThat(JsonPath.<List<?>>read(again, "$.runs")).isEmpty();
  }

  @Test
  void aWorkerClaimsARunOnceAndItsStepsAndResultAreShown() throws Exception {
    UUID resource = item("RESOURCE_CANDIDATE", "OPEN");
    String body = start("{\"itemIds\":[\"%s\"]}".formatted(resource));
    UUID runId = UUID.fromString(JsonPath.read(body, "$.runs[0].id"));

    ResearchRun claimed = runs.claimNext(Instant.now()).orElseThrow();
    assertThat(claimed.id()).isEqualTo(runId);
    assertThat(runs.claimNext(Instant.now())).isEmpty();
    runs.step(
        new ResearchStep(
            runId,
            1,
            ResearchStep.Kind.TOOL_CALL,
            "read_page",
            "{\"url\":\"x\"}",
            "<script>x</script>",
            null,
            12,
            null,
            null,
            Instant.now()));

    String detail =
        mvc.perform(get("/api/v1/admin/review-research/" + runId).with(admin))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(JsonPath.<String>read(detail, "$.run.status")).isEqualTo("RUNNING");
    assertThat(JsonPath.<String>read(detail, "$.steps[0].response"))
        .isEqualTo("<script>x</script>");

    String list =
        mvc.perform(
                get("/api/v1/admin/review-research")
                    .param("itemId", resource.toString())
                    .with(admin))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(JsonPath.<Integer>read(list, "$.runs[0].steps")).isEqualTo(1);
  }

  @Test
  void aCancelledRunIsCancelledOnceAndThePurgeTakesItAway() throws Exception {
    UUID resource = item("RESOURCE_CANDIDATE", "OPEN");
    UUID runId =
        UUID.fromString(
            JsonPath.read(start("{\"itemIds\":[\"%s\"]}".formatted(resource)), "$.runs[0].id"));

    mvc.perform(
            post("/api/v1/admin/review-research/" + runId + "/cancel")
                .with(admin)
                .with(csrf().asHeader()))
        .andExpect(status().isNoContent());
    mvc.perform(
            post("/api/v1/admin/review-research/" + runId + "/cancel")
                .with(admin)
                .with(csrf().asHeader()))
        .andExpect(status().isConflict());
    assertThat(runs.find(runId).orElseThrow().status()).isEqualTo(ResearchRun.Status.CANCELLED);

    assertThat(runs.purge(Instant.now().plusSeconds(60))).isPositive();
    assertThat(runs.find(runId)).isEmpty();
  }

  @Test
  void aRunThatNeverRanIsPurgedTooAndANewOneStays() throws Exception {
    UUID resource = item("RESOURCE_CANDIDATE", "OPEN");
    UUID runId =
        UUID.fromString(
            JsonPath.read(start("{\"itemIds\":[\"%s\"]}".formatted(resource)), "$.runs[0].id"));

    assertThat(runs.purge(Instant.now().minusSeconds(60))).isZero();
    assertThat(runs.find(runId)).isPresent();

    assertThat(runs.purge(Instant.now().plusSeconds(60))).isPositive();
    assertThat(runs.find(runId)).isEmpty();
  }

  @Test
  void itemIdsAndAllOpenTogetherAreRefusedAndAnUnknownRunIsNotFound() throws Exception {
    mvc.perform(
            post("/api/v1/admin/review-research")
                .with(admin)
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"itemIds\":[\"%s\"],\"allOpen\":true}".formatted(UUID.randomUUID())))
        .andExpect(status().isBadRequest());
    mvc.perform(get("/api/v1/admin/review-research/" + UUID.randomUUID()).with(admin))
        .andExpect(status().isNotFound());
    mvc.perform(get("/api/v1/admin/review-research/estimate")).andExpect(status().isUnauthorized());
  }

  @Test
  void thePublicAppCannotReadTheResearch() {
    assertThat(
            jdbc.sql(
                    "SELECT has_table_privilege('h2_public', 'review_research', 'SELECT')"
                        + " OR has_table_privilege('h2_public', 'review_research_step', 'SELECT')")
                .query(Boolean.class)
                .single())
        .isFalse();
  }

  private void run(UUID item, String status, String verdict) {
    jdbc.sql(
            """
            INSERT INTO review_research (id, review_item_id, status, model, requested_by, finished_at, result)
            VALUES (gen_random_uuid(), :item, :status, 'm', 'test',
                    CASE WHEN :status IN ('DONE', 'FAILED', 'CANCELLED') THEN now() END,
                    CASE WHEN :status = 'DONE'
                         THEN jsonb_build_object('verdict', CAST(:verdict AS text), 'reason', 'r',
                                                 'attributes', jsonb_build_array(), 'dropped', jsonb_build_array())
                    END)
            """)
        .param("item", item)
        .param("status", status)
        .param("verdict", verdict)
        .update();
  }

  private List<String> listed(String research) throws Exception {
    String body =
        mvc.perform(
                get("/api/v1/admin/review-items")
                    .param("status", "OPEN")
                    .param("kind", "RESOURCE_CANDIDATE")
                    .param("research", research)
                    .param("size", "100")
                    .with(admin))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return JsonPath.read(body, "$.items[*].id");
  }

  @Test
  void allOpenSkipsAnItemResearchedDoneAndResearchAgainRunsIt() throws Exception {
    UUID done = item("RESOURCE_CANDIDATE", "OPEN");
    UUID failed = item("RESOURCE_CANDIDATE", "OPEN");
    UUID never = item("COURSE_CANDIDATE", "OPEN");
    run(done, "DONE", "KEEP");
    run(failed, "FAILED", null);
    String estimate =
        mvc.perform(get("/api/v1/admin/review-research/estimate").with(admin))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(JsonPath.<Integer>read(estimate, "$.alreadyResearched")).isPositive();
    assertThat(JsonPath.<Integer>read(estimate, "$.items")).isGreaterThanOrEqualTo(2);

    List<String> queued = JsonPath.read(start("{\"allOpen\":true}"), "$.runs[*].itemId");
    assertThat(queued)
        .contains(failed.toString(), never.toString())
        .doesNotContain(done.toString());
    jdbc.sql("DELETE FROM review_research WHERE status = 'QUEUED'").update();

    List<String> again =
        JsonPath.read(start("{\"itemIds\":[\"%s\"]}".formatted(done)), "$.runs[*].itemId");
    assertThat(again).containsExactly(done.toString());
  }

  @Test
  void aRequeuedRunIsQueuedAgainWithoutItsSteps() throws Exception {
    UUID resource = item("RESOURCE_CANDIDATE", "OPEN");
    UUID runId =
        UUID.fromString(
            JsonPath.read(start("{\"itemIds\":[\"%s\"]}".formatted(resource)), "$.runs[0].id"));
    runs.claimNext(Instant.now()).orElseThrow();
    runs.step(
        new ResearchStep(
            runId, 1, ResearchStep.Kind.MODEL_CALL, "m", "q", "a", null, 1, 10, 2, Instant.now()));

    assertThat(runs.requeue(runId)).isTrue();

    ResearchRun back = runs.find(runId).orElseThrow();
    assertThat(back.status()).isEqualTo(ResearchRun.Status.QUEUED);
    assertThat(back.steps()).isZero();
    assertThat(runs.steps(runId, 0)).isEmpty();
    assertThat(runs.requeue(runId)).isFalse();
    assertThat(runs.claimNext(Instant.now())).map(ResearchRun::id).contains(runId);
  }

  @Test
  void theQueueListsTheItemsOfOneResearchVerdictAcrossPages() throws Exception {
    UUID kept = item("RESOURCE_CANDIDATE", "OPEN");
    UUID rejected = item("RESOURCE_CANDIDATE", "OPEN");
    UUID queued = item("RESOURCE_CANDIDATE", "OPEN");
    UUID never = item("RESOURCE_CANDIDATE", "OPEN");
    run(kept, "DONE", "KEEP");
    run(rejected, "DONE", "REJECT");
    run(queued, "QUEUED", null);

    assertThat(listed("KEEP"))
        .contains(kept.toString())
        .doesNotContain(rejected.toString(), never.toString());
    assertThat(listed("REJECT")).contains(rejected.toString()).doesNotContain(kept.toString());
    assertThat(listed("ACTIVE")).contains(queued.toString()).doesNotContain(kept.toString());
    assertThat(listed("NONE"))
        .contains(never.toString())
        .doesNotContain(kept.toString(), rejected.toString(), queued.toString());
    mvc.perform(get("/api/v1/admin/review-items").param("research", "MAYBE").with(admin))
        .andExpect(status().isBadRequest());
  }
}
