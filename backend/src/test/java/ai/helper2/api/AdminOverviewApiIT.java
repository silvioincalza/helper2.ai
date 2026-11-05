package ai.helper2.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.helper2.examples.CheckOutcome;
import ai.helper2.examples.PoolCounts;
import ai.helper2.examples.usecase.CountHomeExamples;
import ai.helper2.shared.AudiencePath;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/** GET /admin/overview: ADMIN only, the contract's shape, every review kind listed, counts only. */
class AdminOverviewApiIT extends ApiTestBase {

  @Autowired JdbcClient jdbc;

  @Autowired CountHomeExamples countHomeExamples;

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
            "ENRICHMENT_VALIDATION",
            "RELATIONSHIP_CANDIDATE",
            "USER_REPORT",
            "COURSE_CANDIDATE",
            "EXAMPLE_CANDIDATE");
    List<String> budgets = JsonPath.read(body, "$.budgets[*].name");
    assertThat(budgets)
        .as("every budget once, by name")
        .doesNotHaveDuplicates()
        .isSorted()
        .contains("guide");
    assertThat(budgets)
        .as("never a budget per visitor")
        .noneMatch(name -> name.startsWith("guide-client:"));
    assertThat(JsonPath.<List<String>>read(body, "$.homeExamples.shown[*].path"))
        .containsExactlyInAnyOrder("USE_AI", "BUILD_WITH_AI");
    assertThat(JsonPath.<List<String>>read(body, "$.homeExamples.hidden[*].outcome"))
        .as("every outcome that hides an example, never PASS")
        .containsExactlyInAnyOrder("WEAK", "FAIL", "PENDING");
  }

  /** The counts read the stored pool: a paused example counts only as paused. */
  @Test
  void theHomeExampleCountsFollowTheStoredChecksAndStatus() {
    PoolCounts before = countHomeExamples.count();
    String tag = UUID.randomUUID().toString().substring(0, 6);
    insertExample("Shown " + tag, AudiencePath.BUILD_WITH_AI, "ACTIVE", CheckOutcome.PASS);
    insertExample("Weak " + tag, AudiencePath.USE_AI, "ACTIVE", CheckOutcome.WEAK);
    insertExample("Fail " + tag, AudiencePath.USE_AI, "ACTIVE", CheckOutcome.FAIL);
    insertExample("Paused " + tag, AudiencePath.USE_AI, "PAUSED", CheckOutcome.PASS);

    PoolCounts after = countHomeExamples.count();
    assertThat(after.getShown().get(AudiencePath.BUILD_WITH_AI))
        .isEqualTo(before.getShown().get(AudiencePath.BUILD_WITH_AI) + 1);
    assertThat(after.getShown().get(AudiencePath.USE_AI))
        .isEqualTo(before.getShown().get(AudiencePath.USE_AI));
    assertThat(after.getHidden().get(CheckOutcome.WEAK))
        .isEqualTo(before.getHidden().get(CheckOutcome.WEAK) + 1);
    assertThat(after.getHidden().get(CheckOutcome.FAIL))
        .isEqualTo(before.getHidden().get(CheckOutcome.FAIL) + 1);
    assertThat(after.getHidden().get(CheckOutcome.PENDING))
        .isEqualTo(before.getHidden().get(CheckOutcome.PENDING));
    assertThat(after.getPaused()).isEqualTo(before.getPaused() + 1);
  }

  void insertExample(String label, AudiencePath path, String status, CheckOutcome outcome) {
    jdbc.sql(
            """
            INSERT INTO home_example
              (id, label, query, path, status, expected_resource_ids, origin, check_outcome)
            VALUES (:id, :label, :query, :path, :status, ARRAY[:expected]::uuid[], 'CURATED', :outcome)
            """)
        .param("id", UUID.randomUUID())
        .param("label", label)
        .param("query", label.toLowerCase(java.util.Locale.ROOT))
        .param("path", path.name())
        .param("status", status)
        .param("expected", UUID.randomUUID())
        .param("outcome", outcome.name())
        .update();
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
