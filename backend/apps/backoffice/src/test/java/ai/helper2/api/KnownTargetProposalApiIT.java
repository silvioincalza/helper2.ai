package ai.helper2.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.OAuth2LoginRequestPostProcessor;
import org.springframework.test.web.servlet.ResultActions;

class KnownTargetProposalApiIT extends ApiTestBase {

  @Autowired JdbcClient jdbc;

  final String tag = UUID.randomUUID().toString().substring(0, 8);
  final String name = "acme-" + tag;
  final String adminId = "proposals-" + UUID.randomUUID();
  final OAuth2LoginRequestPostProcessor admin =
      oauth2Login()
          .attributes(a -> a.put("sub", adminId))
          .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));

  @AfterEach
  void clean() {
    jdbc.sql("DELETE FROM known_target WHERE name LIKE :name").param("name", "%" + tag).update();
    jdbc.sql(
            """
            DELETE FROM candidate_decision d WHERE d.kind = 'KNOWN_TARGET_CANDIDATE' AND d.decided_at IN
              (SELECT i.resolved_at FROM review_item i
               WHERE i.kind = 'KNOWN_TARGET_CANDIDATE' AND i.details ->> 'key' LIKE :tag)
            """)
        .param("tag", "%" + tag)
        .update();
    jdbc.sql(
            "DELETE FROM review_item WHERE kind = 'KNOWN_TARGET_CANDIDATE' AND details ->> 'key' LIKE :tag")
        .param("tag", "%" + tag)
        .update();
  }

  private UUID proposal() {
    UUID id = UUID.randomUUID();
    jdbc.sql(
            """
            INSERT INTO review_item (id, kind, reason, details)
            VALUES (:id, 'KNOWN_TARGET_CANDIDATE', :reason,
                    jsonb_build_object('key', :name, 'name', :name, 'kind', 'VENDOR', 'rule', 'OWNER', 'count', 6,
                                       'otherNames', '[]'::jsonb, 'examples', '[]'::jsonb))
            """)
        .param("id", id)
        .param("reason", "Owner of 6 GitHub or Hugging Face resources: " + name)
        .param("name", name)
        .update();
    return id;
  }

  private ResultActions decide(UUID item, String decision) throws Exception {
    return mvc.perform(
        put("/api/v1/admin/review-items/" + item + "/resolution")
            .with(admin)
            .with(csrf().asHeader())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\":\"" + decision + "\"}"));
  }

  private List<String> targets() {
    return jdbc.sql(
            "SELECT kind || ' ' || origin || ' ' || added_by FROM known_target WHERE name = :name")
        .param("name", name)
        .query(String.class)
        .list();
  }

  private String statusOf(UUID item) {
    return jdbc.sql("SELECT status FROM review_item WHERE id = :id")
        .param("id", item)
        .query(String.class)
        .single();
  }

  private int decisions() {
    return jdbc.sql(
            """
            SELECT count(*) FROM candidate_decision d JOIN review_item i ON i.resolved_at = d.decided_at
            WHERE d.kind = 'KNOWN_TARGET_CANDIDATE' AND i.kind = 'KNOWN_TARGET_CANDIDATE'
              AND i.details ->> 'key' = :name
            """)
        .param("name", name)
        .query(Integer.class)
        .single();
  }

  @Test
  void theProposalIsListedWithItsDetails() throws Exception {
    UUID item = proposal();

    String listed =
        mvc.perform(
                get("/api/v1/admin/review-items?kind=KNOWN_TARGET_CANDIDATE&size=100").with(admin))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    ContractValidator.assertMatches("ReviewItemPage", listed);
    List<String> details = JsonPath.read(listed, "$.items[?(@.id=='" + item + "')].details");
    assertThat(details).hasSize(1);
    assertThat((String) JsonPath.read(details.getFirst(), "$.name")).isEqualTo(name);
  }

  @Test
  void acceptingAProposalAddsTheKnownTargetAsDiscoveredAndRemembersTheDecision() throws Exception {
    UUID item = proposal();

    decide(item, "ACCEPTED")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ACCEPTED"));

    assertThat(targets()).containsExactly("VENDOR DISCOVERED " + adminId);
    assertThat(decisions()).isEqualTo(1);
    assertThat(
            jdbc.sql(
                    "SELECT action || ' ' || outcome FROM admin_audit WHERE admin_id = :a ORDER BY at, id")
                .param("a", adminId)
                .query(String.class)
                .list())
        .containsExactly("ADD_KNOWN_TARGET ADDED DISCOVERED", "RESOLVE_REVIEW_ITEM ACCEPTED");
    String list =
        mvc.perform(get("/api/v1/admin/known-targets").with(admin))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    ContractValidator.assertMatches("KnownTargets", list);
    List<String> origin = JsonPath.read(list, "$.targets[?(@.name == '" + name + "')].origin");
    assertThat(origin).containsExactly("DISCOVERED");
  }

  @Test
  void rejectingAProposalAddsNothingAndRemembersTheDecision() throws Exception {
    UUID item = proposal();

    decide(item, "REJECTED").andExpect(status().isOk());

    assertThat(targets()).isEmpty();
    assertThat(decisions()).isEqualTo(1);
  }

  @Test
  void aProposalWhoseNameAnotherTargetTookMeanwhileIsA409AndStaysOpen() throws Exception {
    UUID item = proposal();
    mvc.perform(
            post("/api/v1/admin/known-targets")
                .with(admin)
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"kind\":\"SERVICE\",\"name\":\"Other %s\",\"otherNames\":[\"%s\"]}"
                        .formatted(tag, name)))
        .andExpect(status().isCreated());

    decide(item, "ACCEPTED").andExpect(status().isConflict());

    assertThat(statusOf(item)).isEqualTo("OPEN");
    assertThat(targets()).isEmpty();
  }

  private ResultActions change(UUID item, String kind, String shown, String... others)
      throws Exception {
    String list = others.length == 0 ? "" : "\"" + String.join("\",\"", others) + "\"";
    return mvc.perform(
        put("/api/v1/admin/review-items/" + item + "/known-target")
            .with(admin)
            .with(csrf().asHeader())
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                "{\"kind\":\"%s\",\"name\":\"%s\",\"otherNames\":[%s]}"
                    .formatted(kind, shown, list)));
  }

  private List<String> namesOf(String shown) {
    return jdbc.sql(
            """
            SELECT t.kind || ' ' || t.origin || ' ' || n.name FROM known_target t
            JOIN known_target_name n ON n.target_id = t.id WHERE t.name = :name ORDER BY n.name
            """)
        .param("name", shown)
        .query(String.class)
        .list();
  }

  @Test
  void aChangedProposalIsAcceptedAsTheAdminWroteIt() throws Exception {
    UUID item = proposal();
    String shown = "Acme " + tag;

    String changed =
        change(item, "SERVICE", " " + shown + " ", name)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("OPEN"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    ContractValidator.assertMatches("ReviewItem", changed);
    String details = JsonPath.read(changed, "$.details");
    assertThat((String) JsonPath.read(details, "$.name")).isEqualTo(shown);
    assertThat((String) JsonPath.read(details, "$.key")).isEqualTo(name);
    assertThat((Boolean) JsonPath.read(details, "$.edited")).isTrue();

    decide(item, "ACCEPTED").andExpect(status().isOk());

    assertThat(namesOf(shown))
        .containsExactlyInAnyOrder("SERVICE DISCOVERED " + shown, "SERVICE DISCOVERED " + name);
    assertThat(decisions()).isEqualTo(1);
  }

  @Test
  void aProposalNamedAsAnExistingTargetGivesItItsOtherNames() throws Exception {
    UUID item = proposal();
    String existing = "Other " + tag;
    mvc.perform(
            post("/api/v1/admin/known-targets")
                .with(admin)
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"kind\":\"VENDOR\",\"name\":\"%s\"}".formatted(existing)))
        .andExpect(status().isCreated());

    change(item, "VENDOR", existing, name).andExpect(status().isOk());
    decide(item, "ACCEPTED").andExpect(status().isOk());

    assertThat(namesOf(existing))
        .containsExactlyInAnyOrder("VENDOR CURATED " + existing, "VENDOR CURATED " + name);
    assertThat(statusOf(item)).isEqualTo("ACCEPTED");
  }

  @Test
  void aProposalNamedAsATargetOfAnotherKindIsA409() throws Exception {
    UUID item = proposal();
    String existing = "Other " + tag;
    mvc.perform(
            post("/api/v1/admin/known-targets")
                .with(admin)
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"kind\":\"SERVICE\",\"name\":\"%s\"}".formatted(existing)))
        .andExpect(status().isCreated());

    change(item, "VENDOR", existing, name).andExpect(status().isOk());
    decide(item, "ACCEPTED").andExpect(status().isConflict());

    assertThat(statusOf(item)).isEqualTo("OPEN");
    assertThat(namesOf(existing)).containsExactly("SERVICE CURATED " + existing);
  }

  @Test
  void aChangeIsRefusedForABadNameADecidedItemAndAnItemOfAnotherKind() throws Exception {
    UUID item = proposal();
    change(item, "VENDOR", "x").andExpect(status().isBadRequest());
    change(UUID.randomUUID(), "VENDOR", "Nobody " + tag).andExpect(status().isNotFound());

    decide(item, "REJECTED").andExpect(status().isOk());
    change(item, "VENDOR", "Late " + tag)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("already-decided"));

    UUID other = UUID.randomUUID();
    jdbc.sql(
            "INSERT INTO review_item (id, kind, candidate_url, reason, note)"
                + " VALUES (:id, 'COURSE_CANDIDATE', :url, 'A course', 'URL')")
        .param("id", other)
        .param("url", "https://courses.example/" + other)
        .update();
    try {
      change(other, "VENDOR", "Course " + tag)
          .andExpect(status().isConflict())
          .andExpect(jsonPath("$.code").value("not-applicable"));
    } finally {
      jdbc.sql("DELETE FROM review_item WHERE id = :id").param("id", other).update();
    }
  }

  @Test
  void aProposalIsNotAcceptedInBulk() throws Exception {
    UUID item = proposal();

    mvc.perform(
            post("/api/v1/admin/review-items/resolutions")
                .with(admin)
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"ids\":[\"" + item + "\"],\"status\":\"ACCEPTED\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].outcome").value("NOT_ALLOWED"));

    assertThat(statusOf(item)).isEqualTo("OPEN");
  }
}
