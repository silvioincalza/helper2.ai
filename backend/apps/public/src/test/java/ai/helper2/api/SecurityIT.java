package ai.helper2.api;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

class SecurityIT extends ApiTestBase {

  @Test
  void anonymousSessionInfo() throws Exception {
    mvc.perform(get("/api/v1/me"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.authenticated").value(false))
        .andExpect(jsonPath("$.limits.guideQuestions").doesNotExist())
        .andExpect(jsonPath("$.limits.webSearches.search").doesNotExist())
        .andExpect(jsonPath("$.limits.resetsAt").isString());
  }

  @Test
  void unknownApiPathsAreDenied() throws Exception {
    mvc.perform(get("/api/v1/does-not-exist")).andExpect(status().isUnauthorized());
    mvc.perform(put("/api/v1/sources").with(csrf().asHeader()))
        .andExpect(status().isUnauthorized());
  }

  /** The back office is another app (ADR 0032): its paths are unknown here. */
  @Test
  void theBackOfficeIsNotHere() throws Exception {
    mvc.perform(get("/api/v1/admin/review-items")).andExpect(status().isNotFound());
    mvc.perform(
            get("/api/v1/admin/review-items")
                .with(oauth2Login().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
        .andExpect(status().isNotFound());
  }

  @Test
  void metricsAreNotPublicByDefault() throws Exception {
    mvc.perform(get("/actuator/prometheus")).andExpect(status().isUnauthorized());
    mvc.perform(get("/actuator/health")).andExpect(status().isOk());
  }
}
