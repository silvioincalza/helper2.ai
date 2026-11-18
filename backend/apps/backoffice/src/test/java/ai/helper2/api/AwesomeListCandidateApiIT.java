package ai.helper2.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.helper2.catalog.ReviewQueue;
import ai.helper2.shared.CanonicalUrl;
import ai.helper2.shared.CollectorIds;
import ai.helper2.shared.ResourceId;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.OAuth2LoginRequestPostProcessor;
import org.springframework.test.web.servlet.ResultActions;

class AwesomeListCandidateApiIT extends ApiTestBase {

  @Autowired JdbcClient jdbc;
  @Autowired ReviewQueue queue;

  final String url =
      CanonicalUrl.of("https://github.com/acme/awesome-candidate-it-" + UUID.randomUUID())
          .getValue();
  final String adminId = "awesome-" + UUID.randomUUID();
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

  UUID propose(String sourceId, String type) {
    String details =
        "{\"version\":2,\"sourceId\":\""
            + sourceId
            + "\",\"url\":\""
            + url
            + "\",\"title\":\"Awesome RAG\",\"type\":\""
            + type
            + "\",\"tags\":[],\"snippet\":\"A curated list of retrieval-augmented generation"
            + " tools.\",\"foundBy\":[{\"sourceId\":\""
            + sourceId
            + "\"}]}";
    assertThat(
            queue.proposeCandidate(
                ReviewQueue.Kind.RESOURCE_CANDIDATE, url, "New " + type, details))
        .isTrue();
    return jdbc.sql("SELECT id FROM review_item WHERE candidate_url = :u")
        .param("u", url)
        .query(UUID.class)
        .single();
  }

  ResultActions edit(UUID item, String fields) throws Exception {
    String seen = queue.find(item).orElseThrow().details();
    String body =
        "{\"expectedDetails\":\""
            + seen.replace("\\", "\\\\").replace("\"", "\\\"")
            + "\","
            + fields.substring(1);
    return mvc.perform(
        put("/api/v1/admin/review-items/" + item + "/candidate")
            .with(admin)
            .with(csrf().asHeader())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
  }

  void accept(UUID item) throws Exception {
    mvc.perform(
            put("/api/v1/admin/review-items/" + item + "/resolution")
                .with(admin)
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"ACCEPTED\"}"))
        .andExpect(status().isOk());
  }

  String storedType() {
    return jdbc.sql("SELECT type FROM resource WHERE canonical_url = :u")
        .param("u", url)
        .query(String.class)
        .single();
  }

  @Test
  void aCandidateEditedIntoAnAwesomeListIsSavedAndAcceptedAsOne() throws Exception {
    UUID item = propose("discovery-self-hosted", "PRODUCT");

    edit(item, "{\"type\":\"AWESOME_LIST\"}").andExpect(status().isOk());
    accept(item);

    assertThat(storedType()).isEqualTo("AWESOME_LIST");
  }

  @Test
  void anAwesomeListTheAwesomeCollectorProposesIsAcceptedAsItCame() throws Exception {
    UUID item = propose(CollectorIds.GITHUB_AWESOME, "AWESOME_LIST");

    accept(item);

    assertThat(storedType()).isEqualTo("AWESOME_LIST");
  }
}
