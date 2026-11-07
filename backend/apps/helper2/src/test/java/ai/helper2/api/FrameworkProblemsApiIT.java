package ai.helper2.api;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * The errors Spring answers on its own (ADR 0003) carry a typed problem and its code, like ours:
 * Spring builds their body late, so they are the ones a handler can miss. The security filters' 401
 * and 403 answer before any controller, so they write the problem themselves.
 */
class FrameworkProblemsApiIT extends ApiTestBase {

  static final String TYPES = "https://helper2.ai/problems/";

  @Test
  void aMethodTheEndpointDoesNotHaveIsATypedMethodNotAllowed() throws Exception {
    mvc.perform(get("/api/v1/resources/00000000-0000-0000-0000-000000000000/reports"))
        .andExpect(status().isMethodNotAllowed())
        .andExpect(jsonPath("$.type").value(TYPES + "method-not-allowed"))
        .andExpect(jsonPath("$.code").value("method-not-allowed"));
  }

  @Test
  void aBodyOfTheWrongMediaTypeIsATypedUnsupportedMediaType() throws Exception {
    mvc.perform(
            post("/api/v1/resources/00000000-0000-0000-0000-000000000000/reports")
                .with(csrf().asHeader())
                .contentType(MediaType.TEXT_PLAIN)
                .content("wrong"))
        .andExpect(status().isUnsupportedMediaType())
        .andExpect(jsonPath("$.type").value(TYPES + "unsupported-media-type"))
        .andExpect(jsonPath("$.code").value("unsupported-media-type"));
  }

  @Test
  void anApiPathNobodyServesIsATypedNotFound() throws Exception {
    mvc.perform(get("/api/v1/resources/00000000-0000-0000-0000-000000000000/no-such-part"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.type").value(TYPES + "not-found"))
        .andExpect(jsonPath("$.code").value("not-found"));
  }

  @Test
  void anAnonymousRequestToASignedInEndpointIsATypedSignInRequired() throws Exception {
    mvc.perform(get("/api/v1/me/bookmarks"))
        .andExpect(status().isUnauthorized())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(jsonPath("$.type").value(TYPES + "sign-in-required"))
        .andExpect(jsonPath("$.code").value("sign-in-required"))
        .andExpect(jsonPath("$.detail").value("Sign in to do this."));
  }

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

  @Test
  void aRequestWithoutTheCsrfTokenIsATypedForbiddenThatSaysSo() throws Exception {
    mvc.perform(delete("/api/v1/me").with(oauth2Login()))
        .andExpect(status().isForbidden())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("forbidden"))
        .andExpect(jsonPath("$.detail").value("The CSRF token is missing or wrong."));
  }
}
