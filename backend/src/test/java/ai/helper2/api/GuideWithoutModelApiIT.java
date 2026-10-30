package ai.helper2.api;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.helper2.shared.ResourceType;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/** No model configured (the default test context): the guide still answers, from search only. */
class GuideWithoutModelApiIT extends ApiTestBase {

    @Test
    void withoutAModelTheGuideDegradesToTopSearchResults() throws Exception {
        FAKE.set(q -> List.of(fakeHit("https://github.com/acme/polyglot", "Polyglot Translate",
            "Polyglot Translate converts whole documents between thirty languages", ResourceType.PRODUCT, "MIT", null, null, 0)));

        String json = mvc.perform(post("/api/v1/guide").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"request\":\"translate documents without a model\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("LLM_UNAVAILABLE"))
            .andExpect(jsonPath("$.model").doesNotExist())
            .andExpect(jsonPath("$.aiContent").doesNotExist())
            .andExpect(jsonPath("$.prompt").doesNotExist())
            .andExpect(jsonPath("$.recommendations[0].aiReasoned").value(false))
            .andExpect(jsonPath("$.recommendations[0].reason").value(org.hamcrest.Matchers.containsString("no AI reasoning")))
            .andReturn().getResponse().getContentAsString();
        ContractValidator.assertMatches("GuideResponse", json);
    }
}
