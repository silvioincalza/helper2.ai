package ai.helper2.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/** GET /admin/overview: ADMIN only, the contract's shape, every review kind listed, counts only. */
class AdminOverviewApiIT extends ApiTestBase {

  @Test
  void theOverviewListsEveryKindAndEveryBudgetForAdminsOnly() throws Exception {
    mvc.perform(get("/api/v1/admin/overview")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/v1/admin/overview").with(oauth2Login()))
        .andExpect(status().isForbidden());

    String body =
        mvc.perform(
                get("/api/v1/admin/overview")
                    .with(oauth2Login().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    ContractValidator.assertMatches("AdminOverview", body);
    assertThat(JsonPath.<List<String>>read(body, "$.openReviewItems[*].kind"))
        .containsExactlyInAnyOrder(
            "ENRICHMENT_VALIDATION", "RELATIONSHIP_CANDIDATE", "USER_REPORT", "COURSE_CANDIDATE");
    List<String> budgets = JsonPath.read(body, "$.budgets[*].name");
    assertThat(budgets)
        .as("every budget once, by name")
        .doesNotHaveDuplicates()
        .isSorted()
        .contains("guide");
  }

  /** One computation a minute: a second call shows the same overview. */
  @Test
  void aSecondCallWithinAMinuteShowsTheSameOverview() throws Exception {
    var admin = oauth2Login().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    String first =
        mvc.perform(get("/api/v1/admin/overview").with(admin))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String second =
        mvc.perform(get("/api/v1/admin/overview").with(admin))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(JsonPath.<String>read(second, "$.generatedAt"))
        .isEqualTo(JsonPath.read(first, "$.generatedAt"));
  }
}
