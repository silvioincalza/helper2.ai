package ai.helper2.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.helper2.shared.ResourceType;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.Test;

class GuideWithoutModelApiIT extends ApiTestBase {

  @Test
  void withoutAModelTheGuideDegradesToTopSearchResults() throws Exception {
    FAKE.set(q ->
        List.of(
            fakeHit(
                "https://github.com/acme/polyglot",
                "Polyglot Translate",
                "Polyglot Translate converts whole documents between thirty languages",
                ResourceType.PRODUCT,
                "MIT",
                null,
                null,
                0)));

    String json =
        GuideAnswers.ask(
                mvc, "{\"request\":\"translate documents without a model\"}", csrf().asHeader())
            .value("$.status", "LLM_UNAVAILABLE")
            .doesNotExist("$.model")
            .doesNotExist("$.aiContent")
            .value("$.recommendations[0].aiReasoned", false)
            .value(
                "$.recommendations[0].reason",
                org.hamcrest.Matchers.containsString("no AI reasoning"))
            .json();
    ContractValidator.assertMatches("GuideResponse", json);
  }

  @Test
  void withoutAModelTheAccountShowsNoGuideQuestionLimit() throws Exception {
    String me =
        mvc.perform(get("/api/v1/me"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    ContractValidator.assertMatches("Me", me);
    assertThat((Object) JsonPath.read(me, "$.limits.guideQuestions")).isNull();
  }
}
