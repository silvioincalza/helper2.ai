package ai.helper2.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.helper2.examples.usecase.AddHomeExample;
import ai.helper2.shared.AudiencePath;
import ai.helper2.testing.AsBackOffice;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * GET /api/v1/home/examples (ADR 0015): only the examples that passed the check, without what the
 * check expects, cached for everyone. The back office adds them (HomeExamplesApiIT there); here the
 * catalog use case it calls stands in for it.
 */
@AsBackOffice
class HomeExamplesPoolApiIT extends ApiTestBase {

  @Autowired JdbcClient jdbc;

  @Autowired AddHomeExample addHomeExample;

  final String tag = UUID.randomUUID().toString().substring(0, 6);

  UUID added(String label, String query) {
    UUID deepl =
        jdbc.sql("SELECT id FROM resource WHERE canonical_url = 'https://deepl.com/translator'")
            .query(UUID.class)
            .single();
    var outcome =
        addHomeExample.add("examples-" + tag, label, query, AudiencePath.USE_AI, List.of(deepl));
    return ((AddHomeExample.Outcome.Added) outcome).example().example().getId();
  }

  @Test
  void theHomePageGetsOnlyExamplesThatPassedTheCheckCachedForEveryone() throws Exception {
    UUID shown = added("Shown " + tag, "translate documents " + tag);
    UUID waiting = added("Waiting " + tag, "translate letters " + tag);
    jdbc.sql("UPDATE home_example SET check_outcome = 'PASS', check_best_rank = 1 WHERE id = :id")
        .param("id", shown)
        .update();

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
}
