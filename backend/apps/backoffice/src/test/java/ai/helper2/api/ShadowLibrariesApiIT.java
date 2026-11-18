package ai.helper2.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.helper2.search.ShadowLibraries;
import ai.helper2.search.ShadowLibraryStore;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.OAuth2LoginRequestPostProcessor;

class ShadowLibrariesApiIT extends ApiTestBase {

  static final String BASE = "/api/v1/admin/shadow-libraries";
  static final String NAME = "api-it-mirror";

  @Autowired JdbcClient jdbc;
  @Autowired ShadowLibraryStore store;
  @Autowired ShadowLibraries libraries;

  final String adminId = "shadow-" + UUID.randomUUID();
  final OAuth2LoginRequestPostProcessor admin =
      oauth2Login()
          .attributes(a -> a.put("sub", adminId))
          .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));

  @AfterEach
  void clean() {
    store.remove(NAME);
    libraries.reload();
  }

  List<String> audit() {
    return jdbc.sql(
            """
            SELECT action || ' ' || target_id || ' ' || outcome || ' ' || coalesce(before_value, '-')
                   || ' ' || coalesce(after_value, '-') || ' ' || coalesce(reason, '-')
            FROM admin_audit WHERE admin_id = :a ORDER BY at, id
            """)
        .param("a", adminId)
        .query(String.class)
        .list();
  }

  @Test
  void anAdminListsAddsAndRemovesANameAndTheSearchFollowsAtOnce() throws Exception {
    String list =
        mvc.perform(get(BASE).with(admin))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.max").value(200))
            .andReturn()
            .getResponse()
            .getContentAsString();
    ContractValidator.assertMatches("ShadowLibraries", list);
    assertThat(list).contains("\"name\":\"libgen\"").contains("\"addedBy\":\"migration\"");
    assertThat(libraries.isShadowLibrary("libgen.rs")).isTrue();

    String refused =
        mvc.perform(
                post(BASE)
                    .with(admin)
                    .with(csrf().asHeader())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"" + NAME + ".org\"}"))
            .andExpect(status().isBadRequest())
            .andReturn()
            .getResponse()
            .getContentAsString();
    ContractValidator.assertMatches("Problem", refused);

    String created =
        mvc.perform(
                post(BASE)
                    .with(admin)
                    .with(csrf().asHeader())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\" API-IT-Mirror \",\"reason\":\"pirated books\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.name").value(NAME))
            .andExpect(jsonPath("$.addedBy").value(adminId))
            .andReturn()
            .getResponse()
            .getContentAsString();
    ContractValidator.assertMatches("ShadowLibraryEntry", created);
    assertThat(libraries.isShadowLibrary("ka." + NAME + ".fm")).isTrue();

    mvc.perform(
            post(BASE)
                .with(admin)
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + NAME + "\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("duplicate"));

    mvc.perform(
            post(BASE + "/" + NAME + "/remove")
                .with(admin)
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"a publisher\"}"))
        .andExpect(status().isNoContent());
    mvc.perform(post(BASE + "/" + NAME + "/remove").with(admin).with(csrf().asHeader()))
        .andExpect(status().isNotFound());

    assertThat(libraries.isShadowLibrary("ka." + NAME + ".fm")).isFalse();
    assertThat(audit())
        .containsExactly(
            "ADD_SHADOW_LIBRARY " + NAME + " ADDED - " + NAME + " pirated books",
            "ADD_SHADOW_LIBRARY " + NAME + " ALREADY_LISTED - " + NAME + " -",
            "REMOVE_SHADOW_LIBRARY " + NAME + " REMOVED " + NAME + " - a publisher",
            "REMOVE_SHADOW_LIBRARY " + NAME + " NOT_FOUND - - -");
  }

  @Test
  void onlyAdminsChangeTheListAndOnlyWithTheCsrfToken() throws Exception {
    String body = "{\"name\":\"" + NAME + "\"}";
    mvc.perform(get(BASE)).andExpect(status().isUnauthorized());
    mvc.perform(get(BASE).with(oauth2Login())).andExpect(status().isForbidden());
    mvc.perform(
            post(BASE)
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isUnauthorized());
    mvc.perform(
            post(BASE)
                .with(oauth2Login())
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isForbidden());
    mvc.perform(post(BASE).with(admin).contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isForbidden());
    mvc.perform(post(BASE + "/libgen/remove").with(admin)).andExpect(status().isForbidden());

    assertThat(audit()).isEmpty();
    assertThat(store.names()).contains("libgen").doesNotContain(NAME);
  }
}
