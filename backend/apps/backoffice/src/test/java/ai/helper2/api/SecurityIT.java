package ai.helper2.api;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

class SecurityIT extends ApiTestBase {

  @Test
  void anonymousSessionInfoWithoutTheSitesLimits() throws Exception {
    mvc.perform(get("/api/v1/me"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.authenticated").value(false))
        .andExpect(jsonPath("$.limits.guideQuestions").doesNotExist())
        .andExpect(jsonPath("$.limits.setupGuides").doesNotExist())
        .andExpect(jsonPath("$.limits.resetsAt").isString())
        .andExpect(jsonPath("$.monetisation").doesNotExist());
  }

  @Test
  void anAllowListedSignInIsAnAdminHere() throws Exception {
    mvc.perform(
            get("/api/v1/me")
                .with(oauth2Login().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.roles[0]").value("ADMIN"));
  }

  @Test
  void adminEndpointsRequireAdmin() throws Exception {
    mvc.perform(get("/api/v1/admin/review-items")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/v1/admin/review-items").with(oauth2Login()))
        .andExpect(status().isForbidden());
    mvc.perform(
            get("/api/v1/admin/review-items")
                .with(oauth2Login().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
        .andExpect(status().isOk());
  }

  @Test
  void anAdminRechecksStoredEnrichmentAnswers() throws Exception {
    mvc.perform(
            post("/api/v1/admin/enrichment-recheck").with(oauth2Login()).with(csrf().asHeader()))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/v1/admin/enrichment-recheck")
                .with(oauth2Login().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .with(csrf().asHeader()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.checked").isNumber())
        .andExpect(jsonPath("$.fieldsRejected").isNumber())
        .andExpect(jsonPath("$.claimsDropped").isNumber())
        .andExpect(jsonPath("$.runsRequested").isNumber());
  }

  @Test
  void adminWritesWithoutTheCsrfTokenAreRefused() throws Exception {
    var admin = oauth2Login().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    mvc.perform(
            put("/api/v1/admin/review-items/" + java.util.UUID.randomUUID() + "/resolution")
                .with(admin)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"status\":\"ACCEPTED\"}"))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/v1/admin/scout-runs")
                .with(admin)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void theAdminReadsAreForAdminsOnly() throws Exception {
    mvc.perform(get("/api/v1/sources")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/v1/categories").with(oauth2Login())).andExpect(status().isForbidden());
    mvc.perform(
            get("/api/v1/categories")
                .with(oauth2Login().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
        .andExpect(status().isOk());
  }

  @Test
  void unknownAndPublicApiPathsAreNotFound() throws Exception {
    mvc.perform(get("/api/v1/does-not-exist")).andExpect(status().isNotFound());
    mvc.perform(get("/api/v1/recent")).andExpect(status().isNotFound());
    mvc.perform(delete("/api/v1/me").with(csrf().asHeader())).andExpect(status().isNotFound());
    mvc.perform(get("/api/v1/me/bookmarks")).andExpect(status().isNotFound());
    mvc.perform(put("/api/v1/sources").with(csrf().asHeader())).andExpect(status().isNotFound());
  }

  @Test
  void metricsAreNotPublicByDefault() throws Exception {
    mvc.perform(get("/actuator/prometheus")).andExpect(status().isUnauthorized());
    mvc.perform(get("/actuator/health")).andExpect(status().isOk());
  }
}
