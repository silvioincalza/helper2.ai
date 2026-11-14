package ai.helper2.api;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

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
