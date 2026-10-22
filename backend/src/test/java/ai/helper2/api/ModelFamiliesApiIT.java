package ai.helper2.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The comparison page data: the model-family seed (R__model_families.sql), each official spec with its provider page and verbatim excerpt. */
class ModelFamiliesApiIT extends ApiTestBase {

    @Test
    void everySeededFamilyComesWithSourcedSpecs() throws Exception {
        String body = mvc.perform(get("/api/v1/model-families"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        ContractValidator.assertMatches("ModelFamiliesResponse", body);

        List<String> ids = JsonPath.read(body, "$.families[*].id");
        assertThat(ids).containsExactly("openai", "anthropic", "xai", "google", "meta", "mistral", "deepseek", "qwen", "nvidia");

        List<Map<String, Object>> claude = JsonPath.read(body, "$.families[?(@.id=='anthropic')].specs[*]");
        assertThat(claude).extracting(s -> s.get("field")).contains("flagship", "contextTokens", "inputUsdPerMTok");
        List<Object> withoutEvidence = JsonPath.read(body, "$.families[*].specs[?(@.evidence.length() == 0)]");
        assertThat(withoutEvidence).isEmpty();
        List<String> urls = JsonPath.read(body, "$.families[*].specs[*].evidence[*].sourceUrl");
        assertThat(urls).allMatch(u -> u.startsWith("https://"));
        // Closed families: no official page states open weights either way, so it stays unknown.
        List<Object> openAiWeights = JsonPath.read(body, "$.families[?(@.id=='openai')].openWeights");
        assertThat(openAiWeights).containsOnlyNulls();
    }
}
