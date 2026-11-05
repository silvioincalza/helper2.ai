package ai.helper2.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.OAuth2LoginRequestPostProcessor;
import org.springframework.test.web.servlet.ResultActions;

/**
 * POST /admin/enrichment-queue/{id}/requeue|skip|prioritise (ADR 0045 §7): ADMIN only with CSRF,
 * 404 and 409 in the contract's shape, the new state in the queue views, one audit row per call.
 */
class EnrichmentQueueActionsApiIT extends ApiTestBase {

  static final String BASE = "/api/v1/admin/enrichment-queue/";
  static final String TEXT = "A description long enough for the model to cite something.";

  @Autowired JdbcClient jdbc;

  final String adminId = "queue-" + UUID.randomUUID();
  final OAuth2LoginRequestPostProcessor admin =
      oauth2Login()
          .attributes(a -> a.put("sub", adminId))
          .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
  final List<UUID> created = new ArrayList<>();

  @AfterEach
  void clean() {
    created.forEach(id -> jdbc.sql("DELETE FROM resource WHERE id = :id").param("id", id).update());
  }

  /** A resource the backlog rule takes, with enough source text. */
  UUID resource(String status) {
    UUID id = UUID.randomUUID();
    String url = "https://queue-actions.example/" + id;
    jdbc.sql(
            """
            INSERT INTO resource (id, canonical_url, url, title, description_origin, type, audience, data_origin,
                                  enrichment_status, first_discovered_at)
            VALUES (:id, :url, :url, :title, 'SOURCE_PROVIDED', 'OTHER', 'BUILD_WITH_AI', 'INDEXED', :status, now())
            """)
        .param("id", id)
        .param("url", url)
        .param("title", "Queue " + id)
        .param("status", status)
        .update();
    jdbc.sql(
            """
            INSERT INTO source_record (id, source_id, external_id, resource_id, source_url, canonical_url, title,
                                       description, type, retrieved_at, fingerprint)
            VALUES (:rec, 'curated', :rec::text, :id, :url, :url, 'Queue', :text, 'MCP_SERVER', now(), 'fp')
            """)
        .param("rec", UUID.randomUUID())
        .param("id", id)
        .param("url", url + "/record")
        .param("text", TEXT)
        .update();
    created.add(id);
    return id;
  }

  void failedRun(UUID resource, int hoursAgo) {
    jdbc.sql(
            """
            INSERT INTO enrichment_run (id, resource_id, provider, model, prompt_version, input_fingerprint, started_at, outcome)
            VALUES (:id, :rid, 'fake', 'm', 'v', 'fp', :at, 'FAILED')
            """)
        .param("id", UUID.randomUUID())
        .param("rid", resource)
        .param("at", Timestamp.from(Instant.now().minus(hoursAgo, ChronoUnit.HOURS)))
        .update();
  }

  ResultActions act(UUID id, String action) throws Exception {
    return mvc.perform(post(BASE + id + "/" + action).with(admin).with(csrf()));
  }

  /** The entry of [id] in [view], page by page, or null; every page matches the contract. */
  Map<String, Object> entry(String view, UUID id) throws Exception {
    String after = null;
    do {
      var request = get("/api/v1/admin/enrichment-queue").param("view", view).param("size", "100");
      if (after != null) {
        request = request.param("after", after);
      }
      String body =
          mvc.perform(request.with(admin))
              .andExpect(status().isOk())
              .andReturn()
              .getResponse()
              .getContentAsString();
      ContractValidator.assertMatches("EnrichmentQueuePage", body);
      List<Map<String, Object>> found =
          JsonPath.read(body, "$.items[?(@.resourceId == '" + id + "')]");
      if (!found.isEmpty()) {
        return found.getFirst();
      }
      after = JsonPath.read(body, "$.next");
    } while (after != null);
    return null;
  }

  List<String> audit() {
    return jdbc.sql(
            "SELECT action || ' ' || outcome FROM admin_audit WHERE admin_id = :a ORDER BY at, id")
        .param("a", adminId)
        .query(String.class)
        .list();
  }

  void assertNotApplicable(ResultActions result) throws Exception {
    String body =
        result
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("not-applicable"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    ContractValidator.assertMatches("Problem", body);
  }

  @Test
  void onlyAdminsActAndOnlyWithTheCsrfToken() throws Exception {
    UUID id = resource("NOT_RUN");
    for (String action : List.of("requeue", "skip", "prioritise")) {
      mvc.perform(post(BASE + id + "/" + action).with(csrf())).andExpect(status().isUnauthorized());
      mvc.perform(post(BASE + id + "/" + action).with(oauth2Login()).with(csrf()))
          .andExpect(status().isForbidden());
      mvc.perform(post(BASE + id + "/" + action).with(admin)).andExpect(status().isForbidden());
      mvc.perform(get(BASE + id + "/" + action).with(admin))
          .andExpect(status().isMethodNotAllowed());
    }
    assertThat(audit()).isEmpty();
    assertThat(entry("WAITING", id)).containsEntry("state", "WAITING");
  }

  @Test
  void anUnknownResourceIsNotFoundAndRecorded() throws Exception {
    UUID unknown = UUID.randomUUID();
    String body =
        act(unknown, "prioritise")
            .andExpect(status().isNotFound())
            .andReturn()
            .getResponse()
            .getContentAsString();
    ContractValidator.assertMatches("Problem", body);
    assertThat(audit()).containsExactly("PRIORITISE_ENRICHMENT NOT_FOUND");
  }

  @Test
  void prioritiseSkipAndRequeueShowInTheViewsAndInTheAuditTrail() throws Exception {
    UUID id = resource("NOT_RUN");

    act(id, "prioritise").andExpect(status().isNoContent());
    assertThat(entry("WAITING", id)).containsEntry("state", "PRIORITISED");
    assertNotApplicable(act(id, "prioritise"));

    mvc.perform(
            post(BASE + id + "/skip")
                .with(admin)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"Not an AI tool\"}"))
        .andExpect(status().isNoContent());
    assertThat(entry("WAITING", id)).isNull();
    assertThat(entry("SKIPPED", id))
        .containsEntry("state", "SKIPPED")
        .containsEntry("skipReason", "Not an AI tool");
    assertNotApplicable(act(id, "skip"));
    assertNotApplicable(act(id, "prioritise"));

    act(id, "requeue").andExpect(status().isNoContent());
    assertThat(entry("SKIPPED", id)).isNull();
    assertThat(entry("WAITING", id)).containsEntry("state", "WAITING");
    assertNotApplicable(act(id, "requeue"));

    assertThat(audit())
        .containsExactly(
            "PRIORITISE_ENRICHMENT PRIORITISED",
            "PRIORITISE_ENRICHMENT ALREADY_PRIORITISED",
            "SKIP_ENRICHMENT SKIPPED",
            "SKIP_ENRICHMENT ALREADY_SKIPPED",
            "PRIORITISE_ENRICHMENT NOT_WAITING",
            "REQUEUE_ENRICHMENT REQUEUED",
            "REQUEUE_ENRICHMENT NOT_STOPPED");
  }

  @Test
  void aResourceOutOfRetriesIsListedAndARequeuePutsItBack() throws Exception {
    UUID id = resource("REJECTED");
    failedRun(id, 3);
    failedRun(id, 2);
    failedRun(id, 1);
    assertThat(entry("WAITING", id)).isNull();
    assertThat(entry("OUT_OF_RETRIES", id))
        .containsEntry("state", "OUT_OF_RETRIES")
        .containsEntry("failedRuns", 3);
    assertNotApplicable(act(id, "skip"));

    act(id, "requeue").andExpect(status().isNoContent());

    assertThat(entry("OUT_OF_RETRIES", id)).isNull();
    assertThat(entry("WAITING", id))
        .containsEntry("state", "WAITING")
        .containsEntry("waiting", "RETRY")
        .containsEntry("failedRuns", 0);
  }

  @Test
  void aReasonLongerThanTheLimitIsABadRequest() throws Exception {
    UUID id = resource("NOT_RUN");
    mvc.perform(
            post(BASE + id + "/skip")
                .with(admin)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"" + "x".repeat(201) + "\"}"))
        .andExpect(status().isBadRequest());
    mvc.perform(get("/api/v1/admin/enrichment-queue").param("view", "EVERYTHING").with(admin))
        .andExpect(status().isBadRequest());
    assertThat(audit()).isEmpty();
  }
}
