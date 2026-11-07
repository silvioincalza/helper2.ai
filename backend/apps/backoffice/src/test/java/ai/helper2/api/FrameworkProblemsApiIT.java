package ai.helper2.api;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * The errors Spring answers on its own (ADR 0003) carry a typed problem and its code, like ours:
 * Spring builds their body late, so they are the ones a handler can miss. The security filters' 401
 * and 403 answer before any controller, so they write the problem themselves.
 */
/** A refusal on the back office is the problem the contract promises (RFC 9457). */
class FrameworkProblemsApiIT extends ApiTestBase {

  static final String TYPES = "https://helper2.ai/problems/";

  @Test
  void aSignedInUserOnAnAdminEndpointIsATypedForbidden() throws Exception {
    mvc.perform(get("/api/v1/admin/overview").with(oauth2Login()))
        .andExpect(status().isForbidden())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.type").value(TYPES + "forbidden"))
        .andExpect(jsonPath("$.code").value("forbidden"))
        .andExpect(jsonPath("$.detail").value("This account may not do this."))
        .andExpect(jsonPath("$.path").doesNotExist())
        .andExpect(jsonPath("$.timestamp").doesNotExist());
  }
}
