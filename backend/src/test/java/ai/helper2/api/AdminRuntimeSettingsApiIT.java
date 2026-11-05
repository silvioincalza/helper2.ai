package ai.helper2.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.helper2.platform.settings.RuntimeSetting;
import ai.helper2.platform.settings.RuntimeSettings;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.OAuth2LoginRequestPostProcessor;

/**
 * The runtime settings (ADR 0058): ADMIN only, CSRF on writes, the range and the version checked by
 * the server, each change in the audit trail with the value before and after, and the replica
 * applies it at once. Only Scout settings change here: no other test of the shared database depends
 * on them. Each test deletes its changes.
 */
class AdminRuntimeSettingsApiIT extends ApiTestBase {

  static final String GAP = "scout.gapDailyCalls";

  @Autowired JdbcClient jdbc;

  @Autowired RuntimeSettings runtime;

  final String adminId = "settings-" + UUID.randomUUID();
  final OAuth2LoginRequestPostProcessor admin =
      oauth2Login()
          .attributes(a -> a.put("sub", adminId))
          .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));

  @AfterEach
  void deleteOwnChanges() {
    jdbc.sql("DELETE FROM runtime_setting WHERE changed_by = :a").param("a", adminId).update();
    runtime.reloadIfChanged();
  }

  @Test
  void onlyAdminsSeeAndChangeTheSettings() throws Exception {
    mvc.perform(get("/api/v1/admin/runtime-settings")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/v1/admin/runtime-settings").with(oauth2Login()))
        .andExpect(status().isForbidden());
    mvc.perform(
            put("/api/v1/admin/runtime-settings/" + GAP)
                .with(oauth2Login())
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(change(5, 0, null)))
        .andExpect(status().isForbidden());
  }

  @Test
  void aWriteWithoutTheCsrfTokenIsRefused() throws Exception {
    mvc.perform(
            put("/api/v1/admin/runtime-settings/" + GAP)
                .with(admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content(change(5, version(), null)))
        .andExpect(status().isForbidden());
    assertThat(audit()).isEmpty();
  }

  @Test
  void everySettingOfTheAllowListIsListedWithItsDefaultAndRange() throws Exception {
    String body = list();
    ContractValidator.assertMatches("RuntimeSettings", body);
    assertThat(JsonPath.<List<String>>read(body, "$.settings[*].key"))
        .containsExactly(
            java.util.Arrays.stream(RuntimeSetting.values())
                .map(RuntimeSetting::key)
                .toArray(String[]::new));
    assertThat(
            JsonPath.<List<Integer>>read(body, "$.settings[?(@.key=='" + GAP + "')].defaultValue"))
        .containsExactly(400);
    assertThat(JsonPath.<List<Integer>>read(body, "$.settings[?(@.key=='" + GAP + "')].max"))
        .containsExactly((int) RuntimeSetting.SCOUT_GAP_DAILY_CALLS.max());
  }

  @Test
  void aChangeAppliesAtOnceAndIsAuditedWithBeforeAndAfter() throws Exception {
    String body =
        mvc.perform(
                put("/api/v1/admin/runtime-settings/" + GAP)
                    .with(admin)
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(change(7, version(), "load test")))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    ContractValidator.assertMatches("RuntimeSettingState", body);
    assertThat(JsonPath.<Integer>read(body, "$.value")).isEqualTo(7);
    assertThat(JsonPath.<Boolean>read(body, "$.overridden")).isTrue();
    assertThat(JsonPath.<String>read(body, "$.history[0].reason")).isEqualTo("load test");
    assertThat(runtime.current(RuntimeSetting.SCOUT_GAP_DAILY_CALLS))
        .as("this replica applies it after the commit")
        .isEqualTo(7L);

    assertThat(audit())
        .containsExactly(
            Map.of(
                "outcome",
                "CHANGED",
                "before",
                "default 400",
                "after",
                "7",
                "reason",
                "load test"));

    String overview =
        mvc.perform(get("/api/v1/admin/overview").with(admin))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    ContractValidator.assertMatches("AdminOverview", overview);

    String audit =
        mvc.perform(get("/api/v1/admin/audit").with(admin))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    ContractValidator.assertMatches("AuditEntryPage", audit);
  }

  @Test
  void aValueOutsideTheRangeIsABadRequest() throws Exception {
    long max = RuntimeSetting.SCOUT_GAP_DAILY_CALLS.max();
    mvc.perform(
            put("/api/v1/admin/runtime-settings/" + GAP)
                .with(admin)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(change(max + 1, version(), null)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("invalid-request"));
    mvc.perform(
            put("/api/v1/admin/runtime-settings/" + GAP)
                .with(admin)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(change(-1, version(), null)))
        .andExpect(status().isBadRequest());
    assertThat(audit()).extracting(r -> r.get("outcome")).containsExactly("OUT_OF_RANGE");
    assertThat(runtime.current(RuntimeSetting.SCOUT_GAP_DAILY_CALLS)).isEqualTo(400L);
  }

  @Test
  void aKeyOutsideTheAllowListIsNotFound() throws Exception {
    mvc.perform(
            put("/api/v1/admin/runtime-settings/models.reasoning")
                .with(admin)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(change(1, 0, null)))
        .andExpect(status().isNotFound());
  }

  @Test
  void aChangeOnAnOldVersionIsAConflictAndAResetBringsTheDefaultBack() throws Exception {
    int seen = version();
    mvc.perform(
            put("/api/v1/admin/runtime-settings/" + GAP)
                .with(admin)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(change(9, seen, null)))
        .andExpect(status().isOk());
    mvc.perform(
            put("/api/v1/admin/runtime-settings/" + GAP)
                .with(admin)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(change(11, seen, null)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("version-conflict"));

    String body =
        mvc.perform(
                post("/api/v1/admin/runtime-settings/" + GAP + "/reset")
                    .with(admin)
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"version\":" + version() + "}"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    ContractValidator.assertMatches("RuntimeSettingState", body);
    assertThat(JsonPath.<Integer>read(body, "$.value")).isEqualTo(400);
    assertThat(JsonPath.<Boolean>read(body, "$.overridden")).isFalse();
    assertThat(JsonPath.<List<Object>>read(body, "$.history[*].value")).containsExactly(null, 9);
    assertThat(runtime.current(RuntimeSetting.SCOUT_GAP_DAILY_CALLS)).isEqualTo(400L);
    assertThat(audit())
        .extracting(r -> r.get("outcome"))
        .containsExactly("CHANGED", "CONFLICT", "RESET");
  }

  private String list() throws Exception {
    return mvc.perform(get("/api/v1/admin/runtime-settings").with(admin))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  private int version() throws Exception {
    return JsonPath.<List<Integer>>read(list(), "$.settings[?(@.key=='" + GAP + "')].version")
        .getFirst();
  }

  private static String change(long value, long version, String reason) {
    return "{\"value\":%d,\"version\":%d,\"reason\":%s}"
        .formatted(value, version, reason == null ? "null" : "\"" + reason + "\"");
  }

  private List<Map<String, String>> audit() {
    return jdbc.sql(
            """
            SELECT outcome, before_value, after_value, reason FROM admin_audit
            WHERE admin_id = :a AND action = 'CHANGE_SETTING' ORDER BY at, outcome
            """)
        .param("a", adminId)
        .query((rs, n) -> {
          Map<String, String> row = new java.util.LinkedHashMap<>();
          row.put("outcome", rs.getString("outcome"));
          if (rs.getString("before_value") != null) {
            row.put("before", rs.getString("before_value"));
          }
          if (rs.getString("after_value") != null) {
            row.put("after", rs.getString("after_value"));
          }
          if (rs.getString("reason") != null) {
            row.put("reason", rs.getString("reason"));
          }
          return row;
        })
        .list();
  }
}
