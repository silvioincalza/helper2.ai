package ai.helper2.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.helper2.examples.ExampleOrigin;
import ai.helper2.testing.AsBackOffice;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@AsBackOffice
class HomeExamplesPoolApiIT extends ApiTestBase {

  @Autowired JdbcClient jdbc;

  final String tag = UUID.randomUUID().toString().substring(0, 6);

  UUID added(String label, String query) {
    return added(label, query, ExampleOrigin.CURATED, null);
  }

  UUID added(String label, String query, ExampleOrigin origin, String model) {
    UUID id = UUID.randomUUID();
    jdbc.sql(
            """
            INSERT INTO home_example (id, label, query, path, expected_resource_ids, origin, model)
            VALUES (:id, :label, :query, 'USE_AI',
                    ARRAY(SELECT id FROM resource WHERE canonical_url = 'https://deepl.com/translator'),
                    :origin, :model)
            """)
        .param("id", id)
        .param("label", label)
        .param("query", query)
        .param("origin", origin.name())
        .param("model", model)
        .update();
    return id;
  }

  private void passed(UUID id) {
    jdbc.sql("UPDATE home_example SET check_outcome = 'PASS', check_best_rank = 1 WHERE id = :id")
        .param("id", id)
        .update();
  }

  private String pool() throws Exception {
    return mvc.perform(get("/api/v1/home/examples"))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  @Test
  void theHomePageGetsOnlyExamplesThatPassedTheCheckCachedForEveryone() throws Exception {
    UUID shown = added("Shown " + tag, "translate documents " + tag);
    UUID waiting = added("Waiting " + tag, "translate letters " + tag);
    passed(shown);

    String body =
        mvc.perform(get("/api/v1/home/examples"))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "max-age=300, public"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    ContractValidator.assertMatches("HomeExamplePool", body);
    assertThat(body)
        .contains(shown.toString())
        .doesNotContain(waiting.toString())
        .doesNotContain("expected")
        .doesNotContain("check");
  }

  @Test
  void anExampleAModelDraftedIsMarkedAsAiWritten() throws Exception {
    UUID drafted =
        added("Aa drafted " + tag, "translate a contract " + tag, ExampleOrigin.AI_DRAFTED, "m-1");
    UUID curated = added("Aa curated " + tag, "translate a recipe " + tag);
    passed(drafted);
    passed(curated);

    String body = pool();
    ContractValidator.assertMatches("HomeExamplePool", body);
    JsonNode pool = JsonMapper.builder().build().readTree(body);
    assertThat(example(pool, drafted).get("aiDrafted").asBoolean()).isTrue();
    assertThat(example(pool, curated).get("aiDrafted").asBoolean()).isFalse();
    JsonNode ai = pool.get("ai");
    assertThat(ai.get("aiGenerated").asBoolean()).isTrue();
    assertThat(ai.get("models").valueStream().map(JsonNode::asString)).contains("m-1");
    assertThat(ai.get("fields").valueStream().map(JsonNode::asString))
        .containsExactly("examples[].label", "examples[].query");
    assertThat(ai.get("policy").asString()).isEqualTo("/mission");
  }

  private static JsonNode example(JsonNode pool, UUID id) {
    return pool.get("examples")
        .valueStream()
        .filter(e -> e.get("id").asString().equals(id.toString()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("example " + id + " is not in the pool"));
  }
}
