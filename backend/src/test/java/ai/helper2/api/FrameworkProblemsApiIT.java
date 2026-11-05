package ai.helper2.api;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * The errors Spring answers on its own (ADR 0003) carry a typed problem and its code, like ours:
 * Spring builds their body late, so they are the ones a handler can miss.
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
                .with(csrf())
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
}
