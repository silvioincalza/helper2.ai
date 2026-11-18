package ai.helper2.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.helper2.catalog.ReviewQueue;
import ai.helper2.platform.ProblemType;
import ai.helper2.research.ResearchAnswer;
import ai.helper2.research.ResearchResult;
import ai.helper2.research.ResearchRun;
import ai.helper2.research.ResearchRuns;
import ai.helper2.shared.CanonicalUrl;
import ai.helper2.shared.ResourceId;
import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.OAuth2LoginRequestPostProcessor;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = "helper2.research.concurrency=0")
class ReviewBulkResearchedApiIT extends ApiTestBase {

  @Autowired JdbcClient jdbc;
  @Autowired ReviewQueue queue;
  @Autowired ResearchRuns runs;

  final String adminId = "bulk-research-" + UUID.randomUUID();
  final OAuth2LoginRequestPostProcessor admin =
      oauth2Login()
          .attributes(a -> a.put("sub", adminId))
          .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
  final List<String> urls = new ArrayList<>();

  @AfterEach
  void clean() {
    for (String url : urls) {
      jdbc.sql("DELETE FROM review_item WHERE candidate_url = :u").param("u", url).update();
      jdbc.sql("DELETE FROM resource WHERE canonical_url = :u").param("u", url).update();
      jdbc.sql("DELETE FROM candidate_decision WHERE candidate_key = :key")
          .param("key", ResourceId.of(CanonicalUrl.of(url)).getValue())
          .update();
    }
  }

  private UUID candidate(String slug) {
    String url =
        CanonicalUrl.of(
                "https://www.engadget.com/bulk-research-it-" + slug + "-" + UUID.randomUUID())
            .getValue();
    urls.add(url);
    String details =
        "{\"version\":2,\"sourceId\":\"discovery-self-hosted\",\"url\":\""
            + url
            + "\",\"title\":\"Acme Notes takes meeting notes | Engadget\",\"type\":\"PRODUCT\","
            + "\"tags\":[\"self-hosted\"],\"snippet\":\"Acme Notes takes notes in calls.\","
            + "\"foundBy\":[{\"sourceId\":\"discovery-self-hosted\"}]}";
    assertThat(
            queue.proposeCandidate(
                ReviewQueue.Kind.RESOURCE_CANDIDATE, url, "New PRODUCT", details))
        .isTrue();
    return jdbc.sql("SELECT id FROM review_item WHERE candidate_url = :u")
        .param("u", url)
        .query(UUID.class)
        .single();
  }

  private static ResearchResult.Attribute attribute(
      String name, String current, String proposed, ResearchResult.Status status) {
    return new ResearchResult.Attribute(name, current, List.of(proposed), status, List.of());
  }

  private void researched(
      UUID item, ResearchAnswer.Verdict verdict, ResearchResult.Attribute... a) {
    runs.enqueue(List.of(item), "test-model", adminId);
    ResearchRun run = runs.claimNext(Instant.now()).orElseThrow();
    assertThat(run.itemId()).isEqualTo(item);
    runs.done(
        run.id(),
        new ResearchResult(verdict, "checked", null, List.of(a), List.of()),
        10,
        5,
        Instant.now());
  }

  private String bulkAccept(List<UUID> ids) throws Exception {
    String list = ids.stream().map(id -> "\"" + id + "\"").collect(Collectors.joining(","));
    return mvc.perform(
            post("/api/v1/admin/review-items/resolutions")
                .with(admin)
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"ACCEPTED\",\"ids\":[" + list + "]}"))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  @Test
  void aCandidateEditedAfterItsResearchIsNotAcceptedWithThatResearch() throws Exception {
    UUID edited = candidate("edited");
    researched(
        edited,
        ResearchAnswer.Verdict.KEEP,
        attribute(
            "title",
            "Acme Notes takes meeting notes | Engadget",
            "Acme Notes",
            ResearchResult.Status.CONFIRMED),
        attribute("license", null, "MIT", ResearchResult.Status.CONFIRMED));
    jdbc.sql(
            "UPDATE review_item SET details = jsonb_set(details, '{title}', '\"Acme Meeting Notes\"')"
                + " WHERE id = :id")
        .param("id", edited)
        .update();

    String body = bulkAccept(List.of(edited));

    assertThat(JsonPath.<List<String>>read(body, "$.items[*].outcome"))
        .containsExactly("NOT_ALLOWED");
    assertThat(itemStatus(edited)).isEqualTo("OPEN");
    assertThat(
            jdbc.sql("SELECT details ->> 'title' FROM review_item WHERE id = :id")
                .param("id", edited)
                .query(String.class)
                .single())
        .isEqualTo("Acme Meeting Notes");
  }

  private String itemStatus(UUID item) {
    return jdbc.sql("SELECT status FROM review_item WHERE id = :id")
        .param("id", item)
        .query(String.class)
        .single();
  }

  @Test
  void onlyCandidatesResearchedAsKeepWithoutConflictAreAcceptedWithTheirConfirmedValues()
      throws Exception {
    UUID keep = candidate("keep");
    researched(
        keep,
        ResearchAnswer.Verdict.KEEP,
        attribute(
            "title",
            "Acme Notes takes meeting notes | Engadget",
            "Acme Notes",
            ResearchResult.Status.CONFIRMED),
        attribute("license", null, "MIT", ResearchResult.Status.CONFIRMED),
        attribute("pricing", null, "PAID", ResearchResult.Status.SINGLE_SOURCE));
    UUID unresearched = candidate("none");
    UUID rejected = candidate("reject");
    researched(rejected, ResearchAnswer.Verdict.REJECT);
    UUID conflict = candidate("conflict");
    researched(
        conflict,
        ResearchAnswer.Verdict.KEEP,
        attribute("license", null, "MIT", ResearchResult.Status.CONFLICT));

    String body = bulkAccept(List.of(keep, unresearched, rejected, conflict));

    ContractValidator.assertMatches("ReviewBulkResult", body);
    assertThat(JsonPath.<List<String>>read(body, "$.items[*].outcome"))
        .containsExactly("RESOLVED", "NOT_ALLOWED", "NOT_ALLOWED", "NOT_ALLOWED");
    assertThat(itemStatus(keep)).isEqualTo("ACCEPTED");
    assertThat(itemStatus(unresearched)).isEqualTo("OPEN");
    assertThat(itemStatus(rejected)).isEqualTo("OPEN");
    assertThat(itemStatus(conflict)).isEqualTo("OPEN");
    var resource =
        jdbc.sql("SELECT title, license, pricing FROM resource WHERE canonical_url = :u")
            .param("u", urls.getFirst())
            .query()
            .singleRow();
    assertThat(resource).containsEntry("title", "Acme Notes").containsEntry("license", "MIT");
    assertThat(resource.get("pricing")).isNull();
  }

  @Test
  void aBulkAcceptStopsAtACandidateWhoseConfirmedValueTheCatalogRefusesAndKeepsTheOnesBeforeIt()
      throws Exception {
    UUID good = candidate("good");
    researched(good, ResearchAnswer.Verdict.KEEP);
    UUID refused = candidate("refused");
    researched(refused, ResearchAnswer.Verdict.KEEP);
    UUID after = candidate("after");
    researched(after, ResearchAnswer.Verdict.KEEP);
    jdbc.sql(
            """
            UPDATE review_item SET details = jsonb_set(details, '{confirmed}', '{"pricing":"CHEAP"}')
            WHERE id = :id
            """)
        .param("id", refused)
        .update();

    mvc.perform(
            post("/api/v1/admin/review-items/resolutions")
                .with(admin)
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"status\":\"ACCEPTED\",\"ids\":[\"%s\",\"%s\",\"%s\"]}"
                        .formatted(good, refused, after)))
        .andExpect(problem(ProblemType.INTERNAL, "Something went wrong on our side."));

    assertThat(itemStatus(good)).isEqualTo("ACCEPTED");
    assertThat(itemStatus(refused)).isEqualTo("OPEN");
    assertThat(itemStatus(after)).isEqualTo("OPEN");
    assertThat(resources(urls.get(0))).isOne();
    assertThat(resources(urls.get(1))).isZero();
    assertThat(resources(urls.get(2))).isZero();
    assertThat(
            jdbc.sql(
                    "SELECT target_id FROM admin_audit WHERE admin_id = :a AND action = 'RESOLVE_REVIEW_ITEM'")
                .param("a", adminId)
                .query(String.class)
                .list())
        .containsExactly(good.toString());
  }

  private long resources(String url) {
    return jdbc.sql("SELECT count(*) FROM resource WHERE canonical_url = :u")
        .param("u", url)
        .query(Long.class)
        .single();
  }
}
