package ai.searchavo.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.OAuth2LoginRequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ReviewItemDetailsApiIT extends ApiTestBase {

  @Autowired JdbcClient jdbc;

  final JsonMapper json = JsonMapper.builder().build();
  final String tag = UUID.randomUUID().toString().substring(0, 8);
  final OAuth2LoginRequestPostProcessor admin =
      oauth2Login()
          .attributes(a -> a.put("sub", "details-" + tag))
          .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
  final List<UUID> items = new ArrayList<>();

  @AfterEach
  void clean() {
    items.forEach(
        id -> jdbc.sql("DELETE FROM review_item WHERE id = :id").param("id", id).update());
  }

  private UUID stored(String kind, @Nullable String details, @Nullable String note) {
    UUID id = UUID.randomUUID();
    jdbc.sql(
            """
            INSERT INTO review_item (id, kind, reason, details, note)
            VALUES (:id, :kind, :reason, CAST(:details AS jsonb), :note)
            """)
        .param("id", id)
        .param("kind", kind)
        .param("reason", "Stored " + kind + " " + tag)
        .param("details", details)
        .param("note", note)
        .update();
    items.add(id);
    return id;
  }

  private String storedText(UUID id) {
    return jdbc.sql("SELECT CAST(details AS text) FROM review_item WHERE id = :id")
        .param("id", id)
        .query(String.class)
        .single();
  }

  private JsonNode listed() throws Exception {
    String page =
        mvc.perform(get("/api/v1/admin/review-items?status=OPEN&size=100").with(admin))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    ContractValidator.assertMatches("ReviewItemPage", page);
    return json.readTree(page).path("items");
  }

  private static JsonNode item(JsonNode listed, UUID id) {
    for (JsonNode item : listed) {
      if (id.toString().equals(item.path("id").asString())) {
        return item;
      }
    }
    throw new AssertionError("item " + id + " is not on the page");
  }

  private static List<String> texts(JsonNode array) {
    List<String> out = new ArrayList<>();
    array.forEach(n -> out.add(n.asString()));
    return out;
  }

  @Test
  void aStoredRowOfEachKindIsListedWithTheDetailsOfItsShape() throws Exception {
    UUID validation = stored("ENRICHMENT_VALIDATION", null, "license: no quote\nsummary: too long");
    UUID relationship =
        stored("RELATIONSHIP_CANDIDATE", null, "The quote does not name the target.");
    UUID report = stored("USER_REPORT", null, "The link is dead.");
    UUID bareReport = stored("USER_REPORT", null, null);
    UUID candidate =
        stored(
            "RESOURCE_CANDIDATE",
            """
            {"version": 2, "sourceId": "discovery-coding", "url": "https://stored-%s.example/",
             "title": "Stored | AI", "type": "PRODUCT", "tags": ["coding", "local-ai"],
             "snippet": "A coding assistant.", "edited": ["title"],
             "confirmed": {"pricing": "FREE"},
             "foundBy": [{"sourceId": "discovery-coding", "discovery": {"rank": 1}},
                         {"sourceId": "discovery-local-ai"}]}
            """
                .formatted(tag),
            null);
    UUID oldCandidate =
        stored(
            "RESOURCE_CANDIDATE",
            """
            {"sourceId": "discovery-coding", "url": "https://old-%s.example/", "title": "Old",
             "type": "PRODUCT", "tags": ["coding"]}
            """
                .formatted(tag),
            null);
    UUID course =
        stored(
            "COURSE_CANDIDATE",
            """
            {"version": 2, "sourceId": "course-scout", "url": "https://course-%s.example/",
             "title": "A primer", "type": "COURSE", "tags": [], "platform": "Coursera",
             "foundBy": [{"sourceId": "course-scout"}]}
            """
                .formatted(tag),
            null);
    UUID oldCourse =
        stored("COURSE_CANDIDATE", null, "URL: https://old-course-" + tag + ".example/");
    UUID proposal =
        stored(
            "KNOWN_TARGET_CANDIDATE",
            """
            {"key": "stored-%1$s", "name": "Stored %1$s", "kind": "SERVICE", "rule": "OWNER", "count": 6,
             "otherNames": ["Stored AI"], "edited": true,
             "examples": [{"id": "00000000-0000-0000-0000-000000000001", "title": "stored/agent"}]}
            """
                .formatted(tag),
            null);
    UUID draft =
        stored(
            "EXAMPLE_CANDIDATE",
            """
            {"label": "Stored %1$s", "query": "summarise a contract %1$s", "path": "BUILD_WITH_AI",
             "model": "nemotron-test"}
            """
                .formatted(tag),
            null);

    JsonNode listed = listed();

    assertThat(item(listed, validation).path("details"))
        .isEqualTo(
            json.readTree(
                "{\"shape\":\"TEXT\",\"text\":\"license: no quote\\nsummary: too long\"}"));
    assertThat(item(listed, relationship).path("details").path("text").asString())
        .isEqualTo("The quote does not name the target.");
    assertThat(item(listed, report).path("details").path("text").asString())
        .isEqualTo("The link is dead.");
    assertThat(item(listed, bareReport).path("details").isNull()).isTrue();

    JsonNode shown = item(listed, candidate).path("details");
    assertThat(shown.path("shape").asString()).isEqualTo("CANDIDATE");
    assertThat(shown.path("url").asString()).isEqualTo("https://stored-" + tag + ".example/");
    assertThat(shown.path("title").asString()).isEqualTo("Stored | AI");
    assertThat(shown.path("type").asString()).isEqualTo("PRODUCT");
    assertThat(texts(shown.path("tags"))).containsExactly("coding", "local-ai");
    assertThat(shown.path("snippet").asString()).isEqualTo("A coding assistant.");
    assertThat(texts(shown.path("foundBy")))
        .containsExactly("discovery-coding", "discovery-local-ai");
    assertThat(texts(shown.path("edited"))).containsExactly("title");
    assertThat(shown.path("revision").asString()).hasSize(64);
    assertThat(texts(item(listed, candidate).path("categories")))
        .containsExactly("coding", "local-ai");

    JsonNode old = item(listed, oldCandidate).path("details");
    assertThat(old.path("shape").asString()).isEqualTo("CANDIDATE");
    assertThat(texts(old.path("foundBy"))).containsExactly("discovery-coding");
    assertThat(old.path("snippet").isNull()).isTrue();
    assertThat(old.path("edited").isEmpty()).isTrue();

    JsonNode shownCourse = item(listed, course).path("details");
    assertThat(shownCourse.path("shape").asString()).isEqualTo("CANDIDATE");
    assertThat(shownCourse.path("type").asString()).isEqualTo("COURSE");
    assertThat(texts(shownCourse.path("foundBy"))).containsExactly("course-scout");
    assertThat(item(listed, oldCourse).path("details"))
        .isEqualTo(
            json.readTree(
                "{\"shape\":\"TEXT\",\"text\":\"URL: https://old-course-" + tag + ".example/\"}"));

    assertThat(item(listed, proposal).path("details"))
        .isEqualTo(
            json.readTree(
                """
                {"shape": "KNOWN_TARGET_PROPOSAL", "name": "Stored %s", "kind": "SERVICE", "rule": "OWNER",
                 "count": 6, "examples": ["stored/agent"], "otherNames": ["Stored AI"], "edited": true}
                """
                    .formatted(tag)));

    assertThat(item(listed, draft).path("draft").path("label").asString())
        .isEqualTo("Stored " + tag);
    assertThat(item(listed, draft).path("details").isNull()).isTrue();
  }

  @Test
  void aStoredValueThatDoesNotReadAsItsShapeIsListedAsTextAndTheRestOfThePageStands()
      throws Exception {
    UUID candidate = stored("RESOURCE_CANDIDATE", "{\"title\": \"No address\"}", null);
    UUID wrongAddress =
        stored(
            "RESOURCE_CANDIDATE",
            "{\"url\": \"ftp://x.example\", \"title\": \"X\", \"type\": \"PRODUCT\"}",
            null);
    UUID proposal =
        stored(
            "KNOWN_TARGET_CANDIDATE",
            "{\"key\": \"p\", \"name\": \"P\", \"kind\": \"PERSON\", \"rule\": \"OWNER\", \"count\": 2}",
            null);
    UUID unknownRule =
        stored(
            "KNOWN_TARGET_CANDIDATE",
            "{\"key\": \"q\", \"name\": \"Q\", \"kind\": \"VENDOR\", \"rule\": \"STARS\", \"count\": 2}",
            null);
    UUID draft = stored("EXAMPLE_CANDIDATE", "{\"label\": \"Only a label\"}", null);
    UUID readable = stored("USER_REPORT", null, "Still listed " + tag);

    JsonNode listed = listed();

    for (UUID id : List.of(candidate, wrongAddress, proposal, unknownRule, draft)) {
      JsonNode details = item(listed, id).path("details");
      assertThat(details.path("shape").asString()).isEqualTo("TEXT");
      assertThat(details.path("text").asString()).isEqualTo(storedText(id));
    }
    assertThat(item(listed, draft).path("draft").isNull()).isTrue();
    assertThat(item(listed, readable).path("details").path("text").asString())
        .isEqualTo("Still listed " + tag);

    String evidence =
        mvc.perform(get("/api/v1/admin/review-items/" + candidate + "/evidence").with(admin))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.item.details.shape").value("TEXT"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    ContractValidator.assertMatches("ReviewEvidence", evidence);
  }

  @Test
  void anEditNamesTheRevisionTheAdminSawAndAnOlderOneIsRefused() throws Exception {
    UUID candidate =
        stored(
            "RESOURCE_CANDIDATE",
            """
            {"version": 2, "sourceId": "discovery-coding", "url": "https://edit-%s.example/",
             "title": "Edit | AI", "type": "PRODUCT", "tags": ["coding"],
             "foundBy": [{"sourceId": "discovery-coding"}]}
            """
                .formatted(tag),
            null);
    jdbc.sql("UPDATE review_item SET candidate_url = :url WHERE id = :id")
        .param("url", "https://edit-" + tag + ".example/")
        .param("id", candidate)
        .update();
    String seen = item(listed(), candidate).path("details").path("revision").asString();

    String edited =
        edit(candidate, seen)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.details.title").value("Edit"))
            .andExpect(jsonPath("$.details.edited[0]").value("title"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    ContractValidator.assertMatches("ReviewItem", edited);
    assertThat(json.readTree(edited).path("details").path("revision").asString())
        .isNotEqualTo(seen);

    edit(candidate, seen)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("version-conflict"));
  }

  private org.springframework.test.web.servlet.ResultActions edit(UUID id, String revision)
      throws Exception {
    return mvc.perform(
        put("/api/v1/admin/review-items/" + id + "/candidate")
            .with(admin)
            .with(csrf().asHeader())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"expectedRevision\":\"" + revision + "\",\"title\":\"Edit\"}"));
  }
}
