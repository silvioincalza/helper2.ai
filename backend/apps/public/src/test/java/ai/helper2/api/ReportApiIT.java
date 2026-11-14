package ai.helper2.api;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.helper2.shared.CanonicalUrl;
import ai.helper2.shared.ResourceId;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class ReportApiIT extends ApiTestBase {

  final String deepl = ResourceId.of(CanonicalUrl.of("https://deepl.com/translator")).toString();

  @Test
  void aReportIsAccepted() throws Exception {
    String accepted =
        mvc.perform(
                post("/api/v1/resources/" + deepl + "/reports")
                    .with(csrf().asHeader())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"kind\":\"OUTDATED\",\"field\":\"description\",\"message\":\"Now also does voice translation\"}"))
            .andExpect(status().isAccepted())
            .andReturn()
            .getResponse()
            .getContentAsString();
    ContractValidator.assertMatches("IssueReportAccepted", accepted);
  }

  @Test
  void reportValidation() throws Exception {
    mvc.perform(
            post("/api/v1/resources/" + deepl + "/reports")
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"kind\":\"WRONG\",\"message\":\"no\"}"))
        .andExpect(status().isBadRequest());
    mvc.perform(
            post("/api/v1/resources/" + deepl + "/reports")
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"kind\":\"NOT_A_KIND\",\"message\":\"long enough\"}"))
        .andExpect(status().isBadRequest());
    mvc.perform(
            post("/api/v1/resources/" + UUID.randomUUID() + "/reports")
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"kind\":\"WRONG\",\"message\":\"long enough\"}"))
        .andExpect(status().isNotFound());
  }
}
