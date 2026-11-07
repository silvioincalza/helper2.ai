package ai.helper2.api;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.helper2.shared.CanonicalUrl;
import ai.helper2.shared.ResourceId;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

class ReportAndReviewIT extends ApiTestBase {

  @org.springframework.beans.factory.annotation.Autowired
  org.springframework.context.ApplicationContext context;

  final String deepl = ResourceId.of(CanonicalUrl.of("https://deepl.com/translator")).toString();

  /**
   * A visitor's report (sent to the public app, ADR 0032) opens a review item here. The report is
   * made through the same catalog use case the public app's endpoint calls.
   */
  @Test
  void reportOpensAReviewItemThatAnAdminCanResolve() throws Exception {
    context
        .getBean(ai.helper2.catalog.usecase.ReportIssue.class)
        .submit(
            UUID.fromString(deepl),
            ai.helper2.catalog.usecase.ReportIssue.Kind.OUTDATED,
            "description",
            "Now also does voice translation",
            null)
        .orElseThrow();

    var admin = oauth2Login().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    String items =
        mvc.perform(
                get("/api/v1/admin/review-items")
                    .param("status", "OPEN")
                    .param("size", "100")
                    .with(admin))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    ContractValidator.assertMatches("ReviewItemPage", items);
    List<String> ids =
        JsonPath.read(
            items, "$.items[?(@.kind=='USER_REPORT' && @.resourceId=='" + deepl + "')].id");
    mvc.perform(
            put("/api/v1/admin/review-items/" + ids.getFirst() + "/resolution")
                .with(admin)
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"ACCEPTED\",\"note\":\"ok\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ACCEPTED"));
  }

  @Test
  void acceptingAResourceCandidateIndexesItAndAnUnreadableOneStaysOpen() throws Exception {
    var queue = context.getBean(ai.helper2.catalog.ReviewQueue.class);
    var jdbc = context.getBean(org.springframework.jdbc.core.simple.JdbcClient.class);
    // Collectors propose canonical URLs.
    String url = CanonicalUrl.of("https://candidate-it.example/").getValue();
    String details =
        "{\"sourceId\":\"discovery-self-hosted\",\"url\":\""
            + url
            + "\",\"title\":\"Candidate IT\",\"type\":\"PRODUCT\",\"tags\":[\"self-hosted\"],"
            + "\"snippet\":\"A self-hosted AI app for LLM chats.\"}";
    org.assertj.core.api.Assertions.assertThat(
            queue.proposeCandidate(
                ai.helper2.catalog.ReviewQueue.Kind.RESOURCE_CANDIDATE,
                url,
                "New PRODUCT",
                details))
        .isTrue();
    UUID item =
        jdbc.sql("SELECT id FROM review_item WHERE candidate_url = :u")
            .param("u", url)
            .query(UUID.class)
            .single();
    var admin = oauth2Login().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));

    mvc.perform(
            put("/api/v1/admin/review-items/" + item + "/resolution")
                .with(admin)
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"ACCEPTED\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ACCEPTED"));

    String resource = ResourceId.of(CanonicalUrl.of(url)).toString();
    var accepted = detail(resource);
    org.assertj.core.api.Assertions.assertThat(accepted.card().getTitle())
        .isEqualTo("Candidate IT");
    org.assertj.core.api.Assertions.assertThat(accepted.card().getType())
        .isEqualTo(ai.helper2.shared.ResourceType.PRODUCT);
    org.assertj.core.api.Assertions.assertThat(accepted.sourceRecords().getFirst().sourceId())
        .isEqualTo("discovery-self-hosted");
    org.assertj.core.api.Assertions.assertThat(accepted.card().getTags()).contains("self-hosted");
    org.assertj.core.api.Assertions.assertThat(
            jdbc.sql(
                    "SELECT count(*) FROM source_record WHERE source_id = 'discovery-self-hosted'"
                        + " AND canonical_url = :u")
                .param("u", url)
                .query(Long.class)
                .single())
        .isEqualTo(1L);

    String badUrl = CanonicalUrl.of("https://candidate-it-bad.example/").getValue();
    queue.proposeCandidate(
        ai.helper2.catalog.ReviewQueue.Kind.RESOURCE_CANDIDATE, badUrl, "New PRODUCT", "not json");
    UUID bad =
        jdbc.sql("SELECT id FROM review_item WHERE candidate_url = :u")
            .param("u", badUrl)
            .query(UUID.class)
            .single();
    mvc.perform(
            put("/api/v1/admin/review-items/" + bad + "/resolution")
                .with(admin)
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"ACCEPTED\"}"))
        .andExpect(status().isBadRequest());
    org.assertj.core.api.Assertions.assertThat(
            jdbc.sql("SELECT status FROM review_item WHERE id = :id")
                .param("id", bad)
                .query(String.class)
                .single())
        .isEqualTo("OPEN");
  }

  @Test
  void twoCategoriesFindingOnePageMakeOneItemThatKeepsWhatBothSearchesSaid() throws Exception {
    var review = context.getBean(ai.helper2.scout.CandidateReview.class);
    var jdbc = context.getBean(org.springframework.jdbc.core.simple.JdbcClient.class);
    CanonicalUrl url = CanonicalUrl.of("https://candidate-it-merged.example/");
    review.propose(
        "discovery-self-hosted",
        List.of(
            new ai.helper2.scout.Candidate(
                url,
                "Merged IT",
                "A self-hosted AI app that runs offline.",
                "self-hosted",
                ai.helper2.shared.ResourceType.PRODUCT,
                java.util.Set.of("self-hosted"),
                java.util.Map.of(
                    "discovery",
                    java.util.Map.of("category", "self-hosted", "query", "q1", "rank", 2)))));
    review.propose(
        "discovery-agent-platforms",
        List.of(
            new ai.helper2.scout.Candidate(
                url,
                "Merged IT",
                "An agent platform.",
                "agent-platforms",
                ai.helper2.shared.ResourceType.AGENT,
                java.util.Set.of("agent-platforms"),
                java.util.Map.of(
                    "discovery",
                    java.util.Map.of("category", "agent-platforms", "query", "q2", "rank", 0)))));
    var admin = oauth2Login().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));

    String listed =
        mvc.perform(get("/api/v1/admin/review-items?category=agent-platforms").with(admin))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    List<String> reasons = JsonPath.read(listed, "$.items[*].reason");
    org.assertj.core.api.Assertions.assertThat(reasons)
        .containsExactly(
            "New AGENT (agent-platforms, self-hosted) found by discovery-self-hosted,"
                + " discovery-agent-platforms: Merged IT");
    List<List<String>> categories = JsonPath.read(listed, "$.items[*].categories");
    org.assertj.core.api.Assertions.assertThat(categories.getFirst())
        .containsExactly("agent-platforms", "self-hosted");
    mvc.perform(get("/api/v1/admin/review-items?category=legal").with(admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(0));
    mvc.perform(get("/api/v1/admin/review-items?category=Not%20a%20tag").with(admin))
        .andExpect(status().isBadRequest());

    UUID item =
        jdbc.sql("SELECT id FROM review_item WHERE candidate_url = :u")
            .param("u", url.getValue())
            .query(UUID.class)
            .single();
    mvc.perform(
            put("/api/v1/admin/review-items/" + item + "/resolution")
                .with(admin)
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"ACCEPTED\"}"))
        .andExpect(status().isOk());
    var record =
        jdbc.sql(
                """
                SELECT source_id, type, extra -> 'discovery' ->> 'query' AS query,
                       jsonb_array_length(extra -> 'foundBy') AS finders,
                       extra -> 'categories' AS categories
                FROM source_record WHERE canonical_url = :u
                """)
            .param("u", url.getValue())
            .query((rs, n) ->
                List.of(
                    rs.getString("source_id"),
                    rs.getString("type"),
                    rs.getString("query"),
                    rs.getString("finders"),
                    rs.getString("categories")))
            .single();
    org.assertj.core.api.Assertions.assertThat(record)
        .containsExactly(
            "discovery-self-hosted", "AGENT", "q1", "2", "[\"agent-platforms\", \"self-hosted\"]");
  }

  @Test
  void acceptingACandidateAnotherSourceIndexedMeanwhileKeepsThatEntry() throws Exception {
    var queue = context.getBean(ai.helper2.catalog.ReviewQueue.class);
    var jdbc = context.getBean(org.springframework.jdbc.core.simple.JdbcClient.class);
    String url = CanonicalUrl.of("https://candidate-it-meanwhile.example/").getValue();
    queue.proposeCandidate(
        ai.helper2.catalog.ReviewQueue.Kind.RESOURCE_CANDIDATE,
        url,
        "New PRODUCT",
        "{\"sourceId\":\"discovery-self-hosted\",\"url\":\""
            + url
            + "\",\"title\":\"From the web\",\"type\":\"PRODUCT\",\"tags\":[]}");
    UUID item =
        jdbc.sql("SELECT id FROM review_item WHERE candidate_url = :u")
            .param("u", url)
            .query(UUID.class)
            .single();
    UUID resource = ResourceId.of(CanonicalUrl.of(url)).getValue();
    jdbc.sql(
            """
            INSERT INTO resource (id, canonical_url, url, title, description_origin, type, audience, data_origin)
            VALUES (:id, :u, :u, 'From another source', 'DEMO', 'LIBRARY', 'BOTH', 'DEMO')
            """)
        .param("id", resource)
        .param("u", url)
        .update();
    var admin = oauth2Login().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));

    mvc.perform(
            put("/api/v1/admin/review-items/" + item + "/resolution")
                .with(admin)
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"ACCEPTED\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ACCEPTED"));
    org.assertj.core.api.Assertions.assertThat(
            jdbc.sql("SELECT title FROM resource WHERE id = :id")
                .param("id", resource)
                .query(String.class)
                .single())
        .isEqualTo("From another source");
  }

  @Test
  void resolvingAnItemOlderThanTheListedOnesStillAnswersIt() throws Exception {
    var queue = context.getBean(ai.helper2.catalog.ReviewQueue.class);
    UUID oldest =
        queue.open(
            ai.helper2.catalog.ReviewQueue.Kind.USER_REPORT,
            null,
            "old",
            null,
            ai.helper2.catalog.ReviewQueue.Links.NONE);
    for (int i = 0; i <= ai.helper2.catalog.usecase.ListReviewItems.MAX_PAGE_SIZE; i++) {
      queue.open(
          ai.helper2.catalog.ReviewQueue.Kind.USER_REPORT,
          null,
          "newer " + i,
          null,
          ai.helper2.catalog.ReviewQueue.Links.NONE);
    }
    var admin = oauth2Login().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    mvc.perform(
            put("/api/v1/admin/review-items/" + oldest + "/resolution")
                .with(admin)
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"REJECTED\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("REJECTED"));
  }

  @Test
  void scoutRunWithAllCollectorsDisabledIsRecordedAsSkipped() throws Exception {
    var admin = oauth2Login().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    mvc.perform(
            post("/api/v1/admin/scout-runs")
                .with(admin)
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$[?(@.sourceId=='mcp-registry')].status").value("SKIPPED"))
        // Every collector: one left enabled here calls the network and holds the run lock other
        // test classes need.
        .andExpect(jsonPath("$[?(@.status != 'SKIPPED')]").isEmpty());
  }

  @Test
  void scoutRunForAnUnknownSourceIsNotFoundAndStartsNothing() throws Exception {
    var admin = oauth2Login().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    mvc.perform(
            post("/api/v1/admin/scout-runs")
                .with(admin)
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sourceId\":\"nope\"}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("not-found"));
  }
}
