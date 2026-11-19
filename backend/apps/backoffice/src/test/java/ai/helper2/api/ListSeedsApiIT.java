package ai.helper2.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.helper2.scout.ListSeed;
import ai.helper2.scout.ListSeedStore;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.OAuth2LoginRequestPostProcessor;

class ListSeedsApiIT extends ApiTestBase {

  static final String BASE = "/api/v1/admin/list-seeds";
  static final String REPOSITORY = "api-it-acme/awesome-rag";

  @Autowired JdbcClient jdbc;
  @Autowired ListSeedStore store;

  final String adminId = "lists-" + UUID.randomUUID();
  final OAuth2LoginRequestPostProcessor admin =
      oauth2Login()
          .attributes(a -> a.put("sub", adminId))
          .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));

  @AfterEach
  void clean() {
    store.remove(REPOSITORY);
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

  String add(String address, String reason) {
    return "{\"address\":\"" + address + "\",\"reason\":" + reason + "}";
  }

  @Test
  void anAdminListsAddsAndRemovesAListAndEachChangeIsAudited() throws Exception {
    String refused =
        mvc.perform(
                post(BASE)
                    .with(admin)
                    .with(csrf().asHeader())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(add("https://www.coursera.org/articles/ai-courses", "null")))
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
                    .content(add("https://github.com/API-IT-Acme/Awesome-RAG", "\"RAG tools\"")))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.repository").value(REPOSITORY))
            .andExpect(jsonPath("$.addedBy").value(adminId))
            .andReturn()
            .getResponse()
            .getContentAsString();
    ContractValidator.assertMatches("ListSeedEntry", created);

    String list =
        mvc.perform(get(BASE).with(admin))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.max").value(50))
            .andReturn()
            .getResponse()
            .getContentAsString();
    ContractValidator.assertMatches("ListSeeds", list);
    assertThat(list).contains("\"repository\":\"" + REPOSITORY + "\"");

    mvc.perform(
            post(BASE)
                .with(admin)
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content(add(REPOSITORY, "null")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("duplicate"));

    mvc.perform(
            post(BASE + "/" + REPOSITORY + "/remove")
                .with(admin)
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"stale\"}"))
        .andExpect(status().isNoContent());
    mvc.perform(post(BASE + "/" + REPOSITORY + "/remove").with(admin).with(csrf().asHeader()))
        .andExpect(status().isNotFound());

    assertThat(store.all()).extracting(ListSeed::getRepository).doesNotContain(REPOSITORY);
    assertThat(audit())
        .containsExactly(
            "ADD_LIST_SEED " + REPOSITORY + " ADDED - " + REPOSITORY + " RAG tools",
            "ADD_LIST_SEED " + REPOSITORY + " ALREADY_LISTED - " + REPOSITORY + " -",
            "REMOVE_LIST_SEED " + REPOSITORY + " REMOVED " + REPOSITORY + " - stale",
            "REMOVE_LIST_SEED " + REPOSITORY + " NOT_FOUND - - -");
  }

  @Test
  void onlyAdminsChangeTheListsAndOnlyWithTheCsrfToken() throws Exception {
    String body = add(REPOSITORY, "null");
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
    mvc.perform(post(BASE + "/" + REPOSITORY + "/remove").with(admin))
        .andExpect(status().isForbidden());

    assertThat(audit()).isEmpty();
    assertThat(store.all()).extracting(ListSeed::getRepository).doesNotContain(REPOSITORY);
  }
}
