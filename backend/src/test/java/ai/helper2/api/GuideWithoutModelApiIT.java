package ai.helper2.api;

import ai.helper2.shared.ResourceType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

/**
 * No model configured (the default test context): the guide still answers, from search only.
 */
class GuideWithoutModelApiIT extends ApiTestBase {

    @Test
    void withoutAModelTheGuideDegradesToTopSearchResults() throws Exception {
        FAKE.set(q -> List.of(fakeHit("https://github.com/acme/polyglot", "Polyglot Translate",
            "Polyglot Translate converts whole documents between thirty languages", ResourceType.PRODUCT, "MIT", null, null, 0)));

        String json = GuideAnswers.ask(mvc, "{\"request\":\"translate documents without a model\"}", csrf())
            .value("$.status", "LLM_UNAVAILABLE")
            .doesNotExist("$.model")
            .doesNotExist("$.aiContent")
            .doesNotExist("$.prompt")
            .value("$.recommendations[0].aiReasoned", false)
            .value("$.recommendations[0].reason", org.hamcrest.Matchers.containsString("no AI reasoning"))
            .json();
        ContractValidator.assertMatches("GuideResponse", json);
    }
}
