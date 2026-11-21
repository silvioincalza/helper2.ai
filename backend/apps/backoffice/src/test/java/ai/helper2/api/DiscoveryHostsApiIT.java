package ai.helper2.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.helper2.scout.DiscoveryHostStore;
import ai.helper2.scout.HostKind;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.OAuth2LoginRequestPostProcessor;

class DiscoveryHostsApiIT extends ApiTestBase {

  static final String BASE = "/api/v1/admin/discovery-hosts";
  static final String HOST = "api-it-news.example";

  @Autowired JdbcClient jdbc;
  @Autowired DiscoveryHostStore store;

  final String adminId = "hosts-" + UUID.randomUUID();
  final OAuth2LoginRequestPostProcessor admin =
      oauth2Login()
          .attributes(a -> a.put("sub", adminId))
          .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));

  @AfterEach
  void clean() {
    store.remove(HOST);
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
  void anAdminListsAddsAndRemovesAHostAndEachChangeIsAudited() throws Exception {
    String list =
        mvc.perform(get(BASE).with(admin))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.max").value(500))
            .andReturn()
            .getResponse()
            .getContentAsString();
    ContractValidator.assertMatches("DiscoveryHosts", list);
    assertThat(list).contains("\"host\":\"techcrunch.com\"").contains("\"addedBy\":\"migration\"");

    String added =
        mvc.perform(
                post(BASE)
                    .with(admin)
                    .with(csrf().asHeader())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"host\":\"https://WWW."
                            + HOST
                            + "\",\"kind\":\"WRITING\",\"reason\":\"tech news\"}"))
            .andExpect(status().isBadRequest())
            .andReturn()
            .getResponse()
            .getContentAsString();
    ContractValidator.assertMatches("Problem", added);

    String created =
        mvc.perform(
                post(BASE)
                    .with(admin)
                    .with(csrf().asHeader())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"host\":\"WWW."
                            + HOST
                            + "\",\"kind\":\"WRITING\",\"reason\":\"tech news\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.host").value(HOST))
            .andExpect(jsonPath("$.addedBy").value(adminId))
            .andReturn()
            .getResponse()
            .getContentAsString();
    ContractValidator.assertMatches("DiscoveryHostEntry", created);
    assertThat(store.lists().getWriting()).contains(HOST);

    mvc.perform(
            post(BASE)
                .with(admin)
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"host\":\"" + HOST + "\",\"kind\":\"DIRECTORY\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("duplicate"));

    mvc.perform(
            post(BASE + "/" + HOST + "/remove")
                .with(admin)
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"not news\"}"))
        .andExpect(status().isNoContent());
    mvc.perform(post(BASE + "/" + HOST + "/remove").with(admin).with(csrf().asHeader()))
        .andExpect(status().isNotFound());

    assertThat(store.all()).noneMatch(h -> h.getHost().equals(HOST));
    assertThat(audit())
        .containsExactly(
            "ADD_DISCOVERY_HOST " + HOST + " ADDED - WRITING tech news",
            "ADD_DISCOVERY_HOST " + HOST + " ALREADY_LISTED - DIRECTORY -",
            "REMOVE_DISCOVERY_HOST " + HOST + " REMOVED WRITING - not news",
            "REMOVE_DISCOVERY_HOST " + HOST + " NOT_FOUND - - -");
  }

  @Test
  void onlyAdminsChangeTheListsAndOnlyWithTheCsrfToken() throws Exception {
    String body = "{\"host\":\"" + HOST + "\",\"kind\":\"" + HostKind.WRITING + "\"}";
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
    mvc.perform(post(BASE + "/medium.com/remove").with(admin)).andExpect(status().isForbidden());

    assertThat(audit()).isEmpty();
    assertThat(store.lists().getWriting()).contains("medium.com").doesNotContain(HOST);
  }

  @Test
  void uc019Br007AnAdminTurnsTheReadingOfADirectoryHostsListsOnAndOffAndEachChangeIsAudited()
      throws Exception {
    String directory = "g2.com";
    try {
      String on =
          mvc.perform(
                  post(BASE + "/" + directory + "/list-reading")
                      .with(admin)
                      .with(csrf().asHeader())
                      .contentType(MediaType.APPLICATION_JSON)
                      .content("{\"read\":true,\"reason\":\"checked the host\"}"))
              .andExpect(status().isOk())
              .andExpect(jsonPath("$.readsLists").value(true))
              .andReturn()
              .getResponse()
              .getContentAsString();
      ContractValidator.assertMatches("DiscoveryHostEntry", on);
      assertThat(store.lists().getDirectoryListsRead()).contains(directory);

      mvc.perform(
              post(BASE + "/medium.com/list-reading")
                  .with(admin)
                  .with(csrf().asHeader())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"read\":true}"))
          .andExpect(status().isBadRequest());
      mvc.perform(
              post(BASE + "/unknown.example/list-reading")
                  .with(admin)
                  .with(csrf().asHeader())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"read\":true}"))
          .andExpect(status().isNotFound());
      mvc.perform(
              post(BASE + "/" + directory + "/list-reading")
                  .with(oauth2Login())
                  .with(csrf().asHeader())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"read\":true}"))
          .andExpect(status().isForbidden());
      mvc.perform(
              post(BASE + "/" + directory + "/list-reading")
                  .with(admin)
                  .with(csrf().asHeader())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"read\":false}"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.readsLists").value(false));

      assertThat(audit())
          .containsExactly(
              "READ_DIRECTORY_LISTS " + directory + " ON OFF ON checked the host",
              "READ_DIRECTORY_LISTS medium.com NOT_A_DIRECTORY OFF ON -",
              "READ_DIRECTORY_LISTS unknown.example NOT_FOUND - ON -",
              "READ_DIRECTORY_LISTS " + directory + " OFF ON OFF -");
    } finally {
      store.readLists(directory, false);
    }
  }
}
