package ai.helper2.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * GET /admin/config: ADMIN only, the contract's shape, the values the typed records hold, and never
 * a secret. Fake secrets are bound here, so the test fails if any of them reaches the page.
 */
@TestPropertySource(
    properties = {
      "helper2.quota.key-secret=" + AdminConfigApiIT.QUOTA_SECRET,
      "helper2.setup.github-token=" + AdminConfigApiIT.GITHUB_TOKEN,
      "helper2.setup.hosts.hf-token=" + AdminConfigApiIT.HF_TOKEN,
      "helper2.admin.grafana-url=https://viewer:"
          + AdminConfigApiIT.URL_PASSWORD
          + "@grafana.test/d",
      "helper2.admin.guide-daily-model-calls=321",
      "helper2.search.web.quota.global-daily-credits=77",
      // Its own context stays cached with the others: a small pool keeps PostgreSQL under its
      // connection limit.
      "spring.datasource.hikari.maximum-pool-size=2",
    })
class AdminConfigApiIT extends ApiTestBase {

  static final String QUOTA_SECRET = "fake-quota-secret-8d1e";
  static final String GITHUB_TOKEN = "ghp_fakeGithubToken4c2a";
  static final String HF_TOKEN = "hf_fakeHuggingFaceToken9b7";
  static final String URL_PASSWORD = "fakeUrlPassword3f";

  static final RequestPostProcessor ADMIN =
      oauth2Login().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));

  @Test
  void onlyAdminsSeeTheSettings() throws Exception {
    mvc.perform(get("/api/v1/admin/config")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/v1/admin/config").with(oauth2Login())).andExpect(status().isForbidden());
  }

  @Test
  void theSettingsAreTheBoundValuesGroupedByModule() throws Exception {
    String body = settings();
    ContractValidator.assertMatches("EffectiveConfig", body);
    assertThat(JsonPath.<List<String>>read(body, "$.groups[*].id"))
        .startsWith("models", "guide", "search", "scout");
    assertThat(
            JsonPath.<List<String>>read(
                body, "$..settings[?(@.key=='guide.dailyModelCalls')].value"))
        .containsExactly("321");
    assertThat(
            JsonPath.<List<String>>read(
                body, "$..settings[?(@.key=='search.webGlobalDailyCredits')].value"))
        .containsExactly("77");
    String sourcesOn =
        JsonPath.<List<String>>read(body, "$..settings[?(@.key=='search.sourcesOn')].value")
            .getFirst();
    assertThat(List.of(sourcesOn.split(","))).contains("fake-live").doesNotContain("github");
    assertThat(JsonPath.<List<String>>read(body, "$..settings[*].key")).doesNotHaveDuplicates();
  }

  @Test
  void noSecretReachesThePage() throws Exception {
    String body = settings();
    assertThat(body)
        .doesNotContain(QUOTA_SECRET, GITHUB_TOKEN, HF_TOKEN, URL_PASSWORD, "admin-subject")
        .doesNotContainIgnoringCase("api-key")
        .doesNotContain("@");
  }

  private String settings() throws Exception {
    return mvc.perform(get("/api/v1/admin/config").with(ADMIN))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }
}
