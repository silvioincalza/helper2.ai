package ai.helper2.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.helper2.catalog.ReviewQueue;
import ai.helper2.scout.usecase.CandidateRevision;
import ai.helper2.shared.CanonicalUrl;
import ai.helper2.shared.CollectorIds;
import ai.helper2.shared.ResourceId;
import ai.helper2.testing.BackendSources;
import java.nio.file.Files;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.OAuth2LoginRequestPostProcessor;

class CourseCandidateApiIT extends ApiTestBase {

  @Autowired JdbcClient jdbc;
  @Autowired ReviewQueue queue;

  final String url =
      CanonicalUrl.of("https://coursera.org/learn/course-candidate-it-" + UUID.randomUUID())
          .getValue();
  final String adminId = "courses-" + UUID.randomUUID();
  final OAuth2LoginRequestPostProcessor admin =
      oauth2Login()
          .attributes(a -> a.put("sub", adminId))
          .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));

  @AfterEach
  void clean() {
    jdbc.sql("DELETE FROM review_item WHERE candidate_url = :u").param("u", url).update();
    jdbc.sql("DELETE FROM resource WHERE canonical_url = :u").param("u", url).update();
    jdbc.sql("DELETE FROM candidate_decision WHERE candidate_key = :key")
        .param("key", ResourceId.of(CanonicalUrl.of(url)).getValue())
        .update();
  }

  UUID propose() {
    String details =
        "{\"version\":2,\"sourceId\":\""
            + CollectorIds.COURSES
            + "\",\"url\":\""
            + url
            + "\",\"title\":\"Generative AI for everyone | Coursera\",\"type\":\"COURSE\","
            + "\"tags\":[],\"snippet\":\"Learn what generative AI can and cannot do.\","
            + "\"platform\":\"Coursera\",\"foundBy\":[{\"sourceId\":\""
            + CollectorIds.COURSES
            + "\"}]}";
    assertThat(
            queue.proposeCandidate(ReviewQueue.Kind.COURSE_CANDIDATE, url, "New course", details))
        .isTrue();
    return jdbc.sql("SELECT id FROM review_item WHERE candidate_url = :u")
        .param("u", url)
        .query(UUID.class)
        .single();
  }

  @Test
  void aCourseCandidateIsEditedAndAcceptedWithEveryConfirmedValue() throws Exception {
    UUID item = propose();
    String seen = queue.find(item).orElseThrow().details();
    String body =
        "{\"expectedRevision\":\""
            + CandidateRevision.of(seen)
            + "\",\"title\":\"Generative AI for Everyone\",\"pricing\":\"FREEMIUM\","
            + "\"techLevel\":\"BEGINNER\",\"language\":\"en\"}";
    mvc.perform(
            put("/api/v1/admin/review-items/" + item + "/candidate")
                .with(admin)
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isOk());
    mvc.perform(
            put("/api/v1/admin/review-items/" + item + "/resolution")
                .with(admin)
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"ACCEPTED\"}"))
        .andExpect(status().isOk());

    var row =
        jdbc.sql(
                "SELECT title, type, pricing, tech_level, language FROM resource WHERE canonical_url = :u")
            .param("u", url)
            .query()
            .singleRow();
    assertThat(row)
        .containsEntry("title", "Generative AI for Everyone")
        .containsEntry("type", "COURSE")
        .containsEntry("pricing", "FREEMIUM")
        .containsEntry("tech_level", "BEGINNER")
        .containsEntry("language", "en");
    assertThat(
            jdbc.sql("SELECT reason FROM review_item WHERE id = :id")
                .param("id", item)
                .query(String.class)
                .single())
        .isEqualTo(
            "New course or certification page found by "
                + CollectorIds.COURSES
                + ": Generative AI for Everyone");
  }

  @Test
  void anOpenCourseHeldAsTextBecomesAStructuredCandidate() throws Exception {
    String note =
        "URL: "
            + url
            + "\nPlatform: Coursera\nSnippet: Learn what generative AI can do.\nAnd more.";
    jdbc.sql(
            """
            INSERT INTO review_item (id, kind, candidate_url, reason, note)
            VALUES (:id, 'COURSE_CANDIDATE', :u, :reason, :note)
            """)
        .param("id", UUID.randomUUID())
        .param("u", url)
        .param(
            "reason",
            "New course or certification page found by course-scout: Generative AI: a primer")
        .param("note", note)
        .update();
    String migration =
        Files.readString(BackendSources.migration("V2026.11.6.5__course_candidate_details.sql"));
    jdbc.sql(migration.substring(migration.indexOf("UPDATE review_item")).strip()).update();

    var row =
        jdbc.sql(
                """
                SELECT details ->> 'sourceId' AS source, details ->> 'url' AS url,
                       details ->> 'title' AS title, details ->> 'type' AS type,
                       details ->> 'snippet' AS snippet, details ->> 'platform' AS platform,
                       details -> 'foundBy' -> 0 ->> 'sourceId' AS finder, note
                FROM review_item WHERE candidate_url = :u
                """)
            .param("u", url)
            .query()
            .singleRow();
    assertThat(row)
        .containsEntry("source", "course-scout")
        .containsEntry("url", url)
        .containsEntry("title", "Generative AI: a primer")
        .containsEntry("type", "COURSE")
        .containsEntry("snippet", "Learn what generative AI can do.\nAnd more.")
        .containsEntry("platform", "Coursera")
        .containsEntry("finder", "course-scout")
        .containsEntry("note", null);
  }
}
