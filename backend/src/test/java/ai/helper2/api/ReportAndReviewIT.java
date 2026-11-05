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

  @Test
  void reportOpensAReviewItemThatAnAdminCanResolve() throws Exception {
    String accepted =
        mvc.perform(
                post("/api/v1/resources/" + deepl + "/reports")
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"kind\":\"OUTDATED\",\"field\":\"description\",\"message\":\"Now also does voice translation\"}"))
            .andExpect(status().isAccepted())
            .andReturn()
            .getResponse()
            .getContentAsString();
    ContractValidator.assertMatches("IssueReportAccepted", accepted);

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
                .with(csrf())
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
        "{\"sourceId\":\"tavily-discovery\",\"url\":\""
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
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"ACCEPTED\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ACCEPTED"));

    String resource = ResourceId.of(CanonicalUrl.of(url)).toString();
    mvc.perform(get("/api/v1/resources/" + resource))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.card.title").value("Candidate IT"))
        .andExpect(jsonPath("$.card.type").value("PRODUCT"))
        .andExpect(jsonPath("$.sourceRecords[0].sourceId").value("tavily-discovery"))
        .andExpect(jsonPath("$.card.tags[0]").value("self-hosted"));
    org.assertj.core.api.Assertions.assertThat(
            jdbc.sql(
                    "SELECT count(*) FROM source_record WHERE source_id = 'tavily-discovery'"
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
                .with(csrf())
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
  void acceptingACandidateAnotherSourceIndexedMeanwhileKeepsThatEntry() throws Exception {
    var queue = context.getBean(ai.helper2.catalog.ReviewQueue.class);
    var jdbc = context.getBean(org.springframework.jdbc.core.simple.JdbcClient.class);
    String url = CanonicalUrl.of("https://candidate-it-meanwhile.example/").getValue();
    queue.proposeCandidate(
        ai.helper2.catalog.ReviewQueue.Kind.RESOURCE_CANDIDATE,
        url,
        "New PRODUCT",
        "{\"sourceId\":\"tavily-discovery\",\"url\":\""
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
                .with(csrf())
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
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"REJECTED\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("REJECTED"));
  }

  @Test
  void reportValidation() throws Exception {
    mvc.perform(
            post("/api/v1/resources/" + deepl + "/reports")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"kind\":\"WRONG\",\"message\":\"no\"}"))
        .andExpect(status().isBadRequest());
    mvc.perform(
            post("/api/v1/resources/" + deepl + "/reports")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"kind\":\"NOT_A_KIND\",\"message\":\"long enough\"}"))
        .andExpect(status().isBadRequest());
    mvc.perform(
            post("/api/v1/resources/" + UUID.randomUUID() + "/reports")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"kind\":\"WRONG\",\"message\":\"long enough\"}"))
        .andExpect(status().isNotFound());
  }

  @Test
  void scoutRunWithAllCollectorsDisabledIsRecordedAsSkipped() throws Exception {
    var admin = oauth2Login().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    mvc.perform(
            post("/api/v1/admin/scout-runs")
                .with(admin)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$[?(@.sourceId=='mcp-registry')].status").value("SKIPPED"))
        // Every collector: one left enabled here calls the network and holds the run lock other
        // test classes need.
        .andExpect(jsonPath("$[?(@.status != 'SKIPPED')]").isEmpty());
  }
}
