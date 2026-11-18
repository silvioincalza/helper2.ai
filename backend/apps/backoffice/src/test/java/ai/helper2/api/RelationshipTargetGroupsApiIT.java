package ai.helper2.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

class RelationshipTargetGroupsApiIT extends ApiTestBase {

  @Autowired JdbcClient jdbc;

  final String name = "Zqtool" + UUID.randomUUID().toString().substring(0, 8);
  final String key = name.toLowerCase();
  final String adminId = "groups-" + UUID.randomUUID();
  final OAuth2LoginRequestPostProcessor admin =
      oauth2Login()
          .attributes(a -> a.put("sub", adminId))
          .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
  final List<UUID> resources = new ArrayList<>();
  final List<UUID> relationships = new ArrayList<>();
  UUID target;

  @BeforeEach
  void insert() {
    for (String written : List.of(name, name.toLowerCase(), " " + name.toUpperCase() + " ")) {
      relationships.add(candidate(resource("Uses " + written), written));
    }
    target = resource(name + " app");
  }

  @AfterEach
  void clean() {
    resources.forEach(
        id -> jdbc.sql("DELETE FROM resource WHERE id = :id").param("id", id).update());
    jdbc.sql("DELETE FROM known_target WHERE lower(name) = :key").param("key", key).update();
  }

  private UUID resource(String title) {
    UUID id = UUID.randomUUID();
    jdbc.sql(
            """
            INSERT INTO resource (id, canonical_url, url, title, description_origin, type, audience, data_origin)
            VALUES (:id, :url, :url, :title, 'SOURCE_PROVIDED', 'LIBRARY', 'BUILD_WITH_AI', 'INDEXED')
            """)
        .param("id", id)
        .param("url", "https://relationship-groups.example/" + id)
        .param("title", title)
        .update();
    resources.add(id);
    return id;
  }

  private UUID candidate(UUID from, String written) {
    UUID relationship = UUID.randomUUID();
    jdbc.sql(
            """
            INSERT INTO relationship (id, from_resource_id, to_title, kind, verification, origin)
            VALUES (:id, :from, :title, 'INTEGRATES_WITH', 'UNVERIFIED', 'AI_EXTRACTED')
            """)
        .param("id", relationship)
        .param("from", from)
        .param("title", written)
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

  private ResultActions decide(String body) throws Exception {
    return mvc.perform(
        post("/api/v1/admin/relationship-targets/decision")
            .with(admin)
            .with(csrf().asHeader())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
  }

  private List<Map<String, Object>> states() {
    return relationships.stream()
        .map(id ->
            jdbc.sql(
                    """
                    SELECT rel.to_resource_id AS target, rel.verification, i.status, i.resolved_by
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

  @Test
  void theOpenCandidatesAreGroupedByTargetWhateverTheCase() throws Exception {
    String body =
        mvc.perform(get("/api/v1/admin/relationship-targets").param("size", "100").with(admin))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    ContractValidator.assertMatches("RelationshipTargetGroupPage", body);
    List<Map<String, Object>> mine = JsonPath.read(body, "$.items[?(@.key=='" + key + "')]");
    assertThat(mine).hasSize(1);
    assertThat(mine.getFirst())
        .containsEntry("items", 3)
        .containsEntry("kinds", List.of("INTEGRATES_WITH"));
    assertThat((List<?>) mine.getFirst().get("examples")).hasSize(3);
  }

  @Test
  void linkingTheGroupPointsEveryItemAtTheResourceAndAcceptsIt() throws Exception {
    String body =
        decide("{\"key\":\"" + key + "\",\"decision\":\"LINK\",\"resourceId\":\"" + target + "\"}")
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    ContractValidator.assertMatches("RelationshipTargetGroupOutcome", body);
    assertThat(JsonPath.<Integer>read(body, "$.decided")).isEqualTo(3);
    assertThat(JsonPath.<Integer>read(body, "$.skipped")).isZero();
    String batch = JsonPath.read(body, "$.batch");

    assertThat(states())
        .allSatisfy(s ->
            assertThat(s)
                .containsEntry("target", target)
                .containsEntry("verification", "VERIFIED")
                .containsEntry("status", "ACCEPTED")
                .containsEntry("resolved_by", adminId));
    assertThat(audit())
        .filteredOn(a -> a.startsWith("RESOLVE_REVIEW_ITEM"))
        .containsOnly("RESOLVE_REVIEW_ITEM ACCEPTED bulk:" + batch)
        .hasSize(3);
    assertThat(audit()).filteredOn(a -> a.startsWith("CHANGE_RELATIONSHIP_TARGET")).hasSize(3);
  }

  @Test
  void aResourceThatIsTheTargetItselfIsSkippedAndStaysOpen() throws Exception {
    UUID itself = resources.getFirst();
    String body =
        decide("{\"key\":\"" + key + "\",\"decision\":\"LINK\",\"resourceId\":\"" + itself + "\"}")
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(JsonPath.<Integer>read(body, "$.decided")).isEqualTo(2);
    assertThat(JsonPath.<Integer>read(body, "$.skipped")).isEqualTo(1);
    assertThat(states().getFirst()).containsEntry("status", "OPEN");
  }

  @Test
  void rejectingTheGroupRejectsEveryItemAndItsRelationship() throws Exception {
    decide("{\"key\":\"" + key + "\",\"decision\":\"REJECT\",\"note\":\"not a tool\"}")
        .andExpect(status().isOk());
    assertThat(states())
        .allSatisfy(s ->
            assertThat(s)
                .containsEntry("verification", "REJECTED")
                .containsEntry("status", "REJECTED"));
  }

  @Test
  void aServiceBecomesAKnownTargetAndClosesTheGroup() throws Exception {
    String body =
        decide("{\"key\":\"" + key + "\",\"decision\":\"SERVICE\"}")
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(JsonPath.<Integer>read(body, "$.decided")).isEqualTo(3);
    assertThat(JsonPath.<Integer>read(body, "$.left")).isZero();
    String batch = JsonPath.read(body, "$.batch");
    assertThat(states())
        .allSatisfy(s ->
            assertThat(s)
                .containsEntry("verification", "VERIFIED")
                .containsEntry("status", "SUPERSEDED"));
    assertThat(audit())
        .filteredOn(a -> a.startsWith("RESOLVE_REVIEW_ITEM"))
        .containsOnly("RESOLVE_REVIEW_ITEM SUPERSEDED bulk:" + batch)
        .hasSize(3);
    assertThat(
            jdbc.sql("SELECT kind FROM known_target WHERE lower(name) = :key")
                .param("key", key)
                .query(String.class)
                .single())
        .isEqualTo("SERVICE");
    assertThat(audit()).anyMatch(a -> a.startsWith("ADD_KNOWN_TARGET"));
  }

  @Test
  void aToolLikeAnEditorBecomesAKnownToolAndItsRelationshipsSayTool() throws Exception {
    decide("{\"key\":\"" + key + "\",\"decision\":\"TOOL\"}").andExpect(status().isOk());
    assertThat(states())
        .allSatisfy(s ->
            assertThat(s)
                .containsEntry("verification", "VERIFIED")
                .containsEntry("status", "SUPERSEDED"));
    assertThat(
            jdbc.sql("SELECT kind FROM known_target WHERE lower(name) = :key")
                .param("key", key)
                .query(String.class)
                .single())
        .isEqualTo("TOOL");
    assertThat(
            jdbc.sql(
                    """
                    SELECT DISTINCT r.target_kind FROM relationship r
                    JOIN review_item i ON i.relationship_id = r.id
                    WHERE lower(r.to_title) = :key
                    """)
                .param("key", key)
                .query(String.class)
                .list())
        .containsExactly("TOOL");
  }

  @Test
  void aServiceClosesOnlyItsOwnGroupNotTheOtherKnownTargetsWaiting() throws Exception {
    UUID other = candidate(resource("Uses OpenAI"), "OpenAI");

    decide("{\"key\":\"" + key + "\",\"decision\":\"SERVICE\"}").andExpect(status().isOk());

    assertThat(
            jdbc.sql("SELECT i.status FROM review_item i WHERE i.relationship_id = :rel")
                .param("rel", other)
                .query(String.class)
                .single())
        .isEqualTo("OPEN");
  }

  @Test
  void aDecidedGroupLeavesNothingAndNewItemsOfTheNameFormTheNextBatch() throws Exception {
    String body =
        decide("{\"key\":\"" + key + "\",\"decision\":\"REJECT\"}")
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(JsonPath.<Integer>read(body, "$.left")).isZero();
    candidate(resource("Uses " + name + " again"), name);
    candidate(resource("Uses " + name + " once more"), name);
    assertThat(
            JsonPath.<Integer>read(
                decide("{\"key\":\"" + key + "\",\"decision\":\"REJECT\"}")
                    .andReturn()
                    .getResponse()
                    .getContentAsString(),
                "$.decided"))
        .isEqualTo(2);
  }

  @Test
  void aLinkNeedsAResourceAndAnUnknownNameIsNotFound() throws Exception {
    decide("{\"key\":\"" + key + "\",\"decision\":\"LINK\"}").andExpect(status().isBadRequest());
    decide("{\"key\":\"" + key + "\",\"decision\":\"REJECT\",\"resourceId\":\"" + target + "\"}")
        .andExpect(status().isBadRequest());
    decide(
            "{\"key\":\""
                + key
                + "\",\"decision\":\"LINK\",\"resourceId\":\""
                + UUID.randomUUID()
                + "\"}")
        .andExpect(status().isBadRequest());
    decide("{\"key\":\"no-such-target-" + UUID.randomUUID() + "\",\"decision\":\"REJECT\"}")
        .andExpect(status().isNotFound());
    assertThat(states()).allSatisfy(s -> assertThat(s).containsEntry("status", "OPEN"));
  }

  @Test
  void onlyAnAdminWithTheCsrfTokenDecides() throws Exception {
    mvc.perform(
            post("/api/v1/admin/relationship-targets/decision")
                .with(admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"key\":\"" + key + "\",\"decision\":\"REJECT\"}"))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/v1/admin/relationship-targets/decision")
                .with(oauth2Login())
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"key\":\"" + key + "\",\"decision\":\"REJECT\"}"))
        .andExpect(status().isForbidden());
  }
}
