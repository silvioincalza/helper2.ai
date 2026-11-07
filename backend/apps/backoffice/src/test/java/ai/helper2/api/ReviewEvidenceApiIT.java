package ai.helper2.api;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.helper2.catalog.ReviewEvidenceFixture;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * GET /admin/review-items/{id}/evidence: ADMIN only, the contract's shape, the run read through the
 * item's link (ADR 0026), and the AI-written values marked (ADR 0018).
 */
class ReviewEvidenceApiIT extends ApiTestBase {

  static final RequestPostProcessor ADMIN =
      oauth2Login().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));

  @Autowired JdbcClient jdbc;

  ReviewEvidenceFixture fixture;

  @BeforeEach
  void oneRunWithItsItems() {
    fixture = new ReviewEvidenceFixture(jdbc).insert();
  }

  @AfterEach
  void clean() {
    fixture.delete();
  }

  private String path(UUID item) {
    return "/api/v1/admin/review-items/" + item + "/evidence";
  }

  @Test
  void onlyAdminsSeeTheEvidence() throws Exception {
    mvc.perform(get(path(fixture.validationItem))).andExpect(status().isUnauthorized());
    mvc.perform(get(path(fixture.validationItem)).with(oauth2Login()))
        .andExpect(status().isForbidden());
  }

  @Test
  void anUnknownItemIsNotFound() throws Exception {
    mvc.perform(get(path(UUID.randomUUID())).with(ADMIN)).andExpect(status().isNotFound());
  }

  @Test
  void aRunWithAConflictingFieldShowsItFirstWithItsQuotes() throws Exception {
    String body =
        mvc.perform(get(path(fixture.validationItem)).with(ADMIN))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.item.id").value(fixture.validationItem.toString()))
            .andExpect(jsonPath("$.item.kind").value("ENRICHMENT_VALIDATION"))
            .andExpect(jsonPath("$.run.model").value("nvidia/nemotron-test"))
            .andExpect(jsonPath("$.run.extraEvidence").value("github-readme:acme/tool"))
            .andExpect(jsonPath("$.validationErrors.length()").value(2))
            .andExpect(jsonPath("$.fields[0].field").value("pricing"))
            .andExpect(jsonPath("$.fields[0].verification").value("CONFLICTING"))
            .andExpect(jsonPath("$.fields[0].quotes.length()").value(5))
            .andExpect(jsonPath("$.fields[0].quotes[0].excerpt").value("Pricing quote 6"))
            .andExpect(
                jsonPath("$.fields[0].quotes[0].sourceId").value(ReviewEvidenceFixture.SOURCE))
            .andExpect(jsonPath("$.relationship").value(Matchers.nullValue()))
            .andExpect(jsonPath("$.aiContent.aiGenerated").value(true))
            .andExpect(jsonPath("$.aiContent.models[0]").value("nvidia/nemotron-test"))
            .andExpect(jsonPath("$.aiContent.fields[0]").value("validationErrors[]"))
            .andExpect(jsonPath("$.aiContent.fields[1]").value("fields[].value"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    ContractValidator.assertMatches("ReviewEvidence", body);
  }

  @Test
  void aRelationshipCandidateShowsTheRelationship() throws Exception {
    String body =
        mvc.perform(get(path(fixture.relationshipItem)).with(ADMIN))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.relationship.target").value("LangChain"))
            .andExpect(jsonPath("$.relationship.quotes[0].excerpt").value("Works with LangChain."))
            .andExpect(jsonPath("$.fields.length()").value(0))
            .andExpect(
                jsonPath("$.aiContent.fields")
                    .value(Matchers.contains("relationship.kind", "relationship.target")))
            .andReturn()
            .getResponse()
            .getContentAsString();
    ContractValidator.assertMatches("ReviewEvidence", body);
  }

  @Test
  void anItemWithNoRunHasOnlyTheItem() throws Exception {
    String body =
        mvc.perform(get(path(fixture.reportItem)).with(ADMIN))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.item.kind").value("USER_REPORT"))
            .andExpect(jsonPath("$.run").value(Matchers.nullValue()))
            .andExpect(jsonPath("$.fields.length()").value(0))
            .andExpect(jsonPath("$.aiContent").value(Matchers.nullValue()))
            .andReturn()
            .getResponse()
            .getContentAsString();
    ContractValidator.assertMatches("ReviewEvidence", body);
  }
}
