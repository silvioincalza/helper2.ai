package ai.helper2.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** GET /api/v1/policy: the numbers of the text pages, from the configuration (ADR 0052). */
class PolicyApiIT extends ApiTestBase {

  private static MockHttpServletRequestBuilder policy(String address) {
    return get("/api/v1/policy")
        .with(r -> {
          r.setRemoteAddr(address);
          return r;
        });
  }

  @Test
  void aGuestReadsTheConfiguredNumbers() throws Exception {
    String body =
        mvc.perform(policy("10.0.51.1"))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "max-age=300, public"))
            .andExpect(jsonPath("$.guide.maxCandidates").value(24))
            .andExpect(jsonPath("$.guide.maxPicks").value(3))
            .andExpect(jsonPath("$.dailyLimits.guideQuestions").value(30))
            .andExpect(jsonPath("$.dailyLimits.webSearches.guest.search").value(3))
            .andExpect(jsonPath("$.dailyLimits.webSearches.guest.guide").value(3))
            .andExpect(
                jsonPath("$.dailyLimits.webSearches.registered")
                    .value(org.hamcrest.Matchers.nullValue()))
            .andExpect(jsonPath("$.retention.cachedSearchesSeconds").value(86_400))
            .andExpect(jsonPath("$.retention.cachedGuideAnswersSeconds").value(86_400))
            .andExpect(jsonPath("$.retention.translationsSeconds").value(86_400))
            .andExpect(jsonPath("$.retention.queryVectorsSeconds").value(604_800))
            .andExpect(jsonPath("$.retention.storedWebResultsSeconds").value(604_800))
            .andExpect(jsonPath("$.retention.inactiveAccountsSeconds").value(63_072_000))
            .andExpect(jsonPath("$.retention.handledReportsSeconds").value(31_536_000))
            .andReturn()
            .getResponse()
            .getContentAsString();
    ContractValidator.assertMatches("Policy", body);
  }

  @Test
  void itIsRateLimitedLikeTheOtherPublicReads() throws Exception {
    for (int i = 0; i < 60; i++) {
      mvc.perform(policy("10.0.51.2")).andExpect(status().isOk());
    }
    mvc.perform(policy("10.0.51.2"))
        .andExpect(status().isTooManyRequests())
        .andExpect(header().exists("Retry-After"));
    mvc.perform(policy("10.0.51.3")).andExpect(status().isOk());
  }
}
