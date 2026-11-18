package ai.helper2.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.helper2.catalog.ReviewQueue;
import ai.helper2.platform.ProblemType;
import ai.helper2.scout.usecase.CandidateRevision;
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
    details = queue.find(item).orElseThrow().details();
  }

  final String listedUrl =
      CanonicalUrl.of("https://candidate-edit-it.example/" + UUID.randomUUID()).getValue();

  @AfterEach
  void clean() {
    jdbc.sql("DELETE FROM resource WHERE canonical_url = :u").param("u", listedUrl).update();
    jdbc.sql("DELETE FROM review_item WHERE candidate_url = :u").param("u", url).update();
    jdbc.sql("DELETE FROM resource WHERE canonical_url = :u").param("u", url).update();
    jdbc.sql("DELETE FROM candidate_decision WHERE candidate_key = :key")
        .param("key", ResourceId.of(CanonicalUrl.of(url)).getValue())
        .update();
  }

  ResultActions edit(UUID id, String fields) throws Exception {
    return edit(id, details, fields);
  }

  ResultActions edit(UUID id, String seen, String fields) throws Exception {
    String expected = "{\"expectedRevision\":\"" + CandidateRevision.of(seen) + "\"";
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
    var card = detail(resource).card();
    assertThat(card.getTitle()).isEqualTo("Google Gemini Carbon promises a leap");
    assertThat(card.getType()).isEqualTo(ai.helper2.shared.ResourceType.NEWS);
    assertThat(card.getTags()).contains("coding");
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
    edit(item, "{\"type\":\"PAPER\"}").andExpect(status().isBadRequest());
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

  void accept() throws Exception {
    mvc.perform(
            put("/api/v1/admin/review-items/" + item + "/resolution")
                .with(admin)
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"ACCEPTED\"}"))
        .andExpect(status().isOk());
  }

  @Test
  void confirmedValuesReachTheResourceAsTheAdminsOwn() throws Exception {
    edit(
            item,
            "{\"pricing\":\"FREEMIUM\",\"license\":\"MIT\",\"openSource\":true,"
                + "\"audience\":\"USE_AI\",\"techLevel\":\"BEGINNER\",\"language\":\"en\","
                + "\"publishedAt\":\"2025-03-02\",\"descriptionWrittenByAi\":true,"
                + "\"description\":\"Gemini Carbon writes code and answers questions.\"}")
        .andExpect(status().isOk());
    accept();

    var row =
        jdbc.sql(
                """
                SELECT pricing, license, open_source, audience, tech_level, language,
                       to_char(published_at AT TIME ZONE 'UTC', 'YYYY-MM-DD') AS published, published_precision,
                       description, description_origin, curated_fields
                FROM resource WHERE canonical_url = :u
                """)
            .param("u", url)
            .query()
            .singleRow();
    assertThat(row)
        .containsEntry("pricing", "FREEMIUM")
        .containsEntry("license", "MIT")
        .containsEntry("open_source", true)
        .containsEntry("audience", "USE_AI")
        .containsEntry("tech_level", "BEGINNER")
        .containsEntry("language", "en")
        .containsEntry("published", "2025-03-02")
        .containsEntry("published_precision", "DAY")
        .containsEntry("description", "Gemini Carbon writes code and answers questions.")
        .containsEntry("description_origin", "AI_SUMMARY");
    assertThat(
            jdbc.sql(
                    "SELECT array_to_string(curated_fields, ',') FROM resource WHERE canonical_url = :u")
                .param("u", url)
                .query(String.class)
                .single()
                .split(","))
        .containsExactlyInAnyOrder(
            "pricing",
            "license",
            "openSource",
            "audience",
            "techLevel",
            "language",
            "publishedAt",
            "description");
    assertThat(audit())
        .contains(
            "EDIT_CANDIDATE EDITED pricing, openSource, license, audience, techLevel, description,"
                + " language, publishedAt",
            "SET_FIELD SET description AI_SUMMARY",
            "SET_FIELD SET publishedAt",
            "SET_FIELD SET pricing");
  }

  @Test
  void anAcceptWhoseConfirmedValueTheCatalogRefusesAddsNothingAndLeavesTheItemOpen()
      throws Exception {
    jdbc.sql(
            """
            UPDATE review_item SET details = jsonb_set(details, '{confirmed}', '{"pricing":"CHEAP"}')
            WHERE id = :id
            """)
        .param("id", item)
        .update();

    mvc.perform(
            put("/api/v1/admin/review-items/" + item + "/resolution")
                .with(admin)
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"ACCEPTED\"}"))
        .andExpect(problem(ProblemType.INVALID_REQUEST, "Invalid request parameters."));

    assertThat(
            jdbc.sql("SELECT count(*) FROM resource WHERE canonical_url = :u")
                .param("u", url)
                .query(Long.class)
                .single())
        .isZero();
    assertThat(
            jdbc.sql("SELECT status FROM review_item WHERE id = :id")
                .param("id", item)
                .query(String.class)
                .single())
        .isEqualTo("OPEN");
    assertThat(audit()).isEmpty();
  }

  @Test
  void aDescriptionTheAdminWroteIsCurated() throws Exception {
    edit(item, "{\"description\":\"Written by the admin.\",\"descriptionWrittenByAi\":false}")
        .andExpect(status().isOk());
    accept();
    assertThat(
            jdbc.sql("SELECT description_origin FROM resource WHERE canonical_url = :u")
                .param("u", url)
                .query(String.class)
                .single())
        .isEqualTo("CURATED");
  }

  @Test
  void aConfirmedUrlTheCatalogListsClosesTheItemAsListed() throws Exception {
    jdbc.sql(
            """
            INSERT INTO resource (id, canonical_url, url, title, description_origin, type, audience,
                                  data_origin)
            VALUES (:id, :u, :u, 'Listed already', 'SOURCE_PROVIDED', 'PRODUCT', 'BOTH', 'INDEXED')
            """)
        .param("id", UUID.randomUUID())
        .param("u", listedUrl)
        .update();
    edit(item, "{\"canonicalUrl\":\"" + listedUrl + "\"}").andExpect(status().isOk());
    accept();
    assertThat(
            jdbc.sql("SELECT count(*) FROM resource WHERE canonical_url = :u")
                .param("u", url)
                .query(Long.class)
                .single())
        .isZero();
    assertThat(
            jdbc.sql("SELECT resolution_note FROM review_item WHERE id = :id")
                .param("id", item)
                .query(String.class)
                .single())
        .contains("Already listed as Listed already");
  }

  @Test
  void aRevisionThatIsNot64HexCharactersIsRefusedAndNothingChanges() throws Exception {
    String seen = CandidateRevision.of(details);
    for (String revision :
        List.of(
            "",
            "abc",
            seen.toUpperCase(java.util.Locale.ROOT),
            seen + "0",
            seen.substring(1),
            "g" + seen.substring(1),
            seen + "\\n")) {
      mvc.perform(
              put("/api/v1/admin/review-items/" + item + "/candidate")
                  .with(admin)
                  .with(csrf().asHeader())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"expectedRevision\":\"" + revision + "\",\"title\":\"x\"}"))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("invalid-request"));
    }
    assertThat(
            jdbc.sql("SELECT details FROM review_item WHERE id = :id")
                .param("id", item)
                .query(String.class)
                .single())
        .isEqualTo(details);
    assertThat(audit()).isEmpty();
  }

  @Test
  void aValueTheCatalogDoesNotTakeIsRefused() throws Exception {
    edit(item, "{\"pricing\":\"CHEAP\"}").andExpect(status().isBadRequest());
    edit(item, "{\"license\":\"MIT license\"}").andExpect(status().isBadRequest());
    edit(item, "{\"canonicalUrl\":\"http://plain.example/\"}").andExpect(status().isBadRequest());
    edit(item, "{\"language\":\"xx\"}").andExpect(status().isBadRequest());
    edit(item, "{\"publishedAt\":\"2999-01-01\"}").andExpect(status().isBadRequest());
    assertThat(audit()).isEmpty();
  }

  @Test
  void anAcceptOfACandidateThatCannotBeReadIsTheInvalidRequestProblemAndTheItemStaysOpen()
      throws Exception {
    jdbc.sql("UPDATE review_item SET details = '{\"version\":2}' WHERE id = :id")
        .param("id", item)
        .update();

    mvc.perform(
            put("/api/v1/admin/review-items/" + item + "/resolution")
                .with(admin)
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"ACCEPTED\"}"))
        .andExpect(problem(ProblemType.INVALID_REQUEST, "The candidate cannot be read."));

    assertThat(
            jdbc.sql("SELECT count(*) FROM resource WHERE canonical_url = :u")
                .param("u", url)
                .query(Long.class)
                .single())
        .isZero();
    assertThat(
            jdbc.sql("SELECT status FROM review_item WHERE id = :id")
                .param("id", item)
                .query(String.class)
                .single())
        .isEqualTo("OPEN");
    assertThat(audit()).isEmpty();
  }
}
