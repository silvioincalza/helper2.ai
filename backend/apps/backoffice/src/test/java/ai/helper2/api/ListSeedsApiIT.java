package ai.helper2.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.helper2.scout.ListAddresses;
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
  static final String PAGE = "https://api-it-blog.acme.dev/best-ai-tools";
  static final String REPOSITORY_ID = ListAddresses.idOf(REPOSITORY).toString();
  static final String PAGE_ID = ListAddresses.idOf(PAGE).toString();

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
    store.remove(PAGE);
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
    String created =
        mvc.perform(
                post(BASE)
                    .with(admin)
                    .with(csrf().asHeader())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(add("https://github.com/API-IT-Acme/Awesome-RAG", "\"RAG tools\"")))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").value(REPOSITORY_ID))
            .andExpect(jsonPath("$.kind").value("GITHUB_REPOSITORY"))
            .andExpect(jsonPath("$.address").value(REPOSITORY))
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
    assertThat(list).contains("\"address\":\"" + REPOSITORY + "\"");

    mvc.perform(
            post(BASE)
                .with(admin)
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content(add(REPOSITORY, "null")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("duplicate"));

    mvc.perform(
            post(BASE + "/" + REPOSITORY_ID + "/remove")
                .with(admin)
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"stale\"}"))
        .andExpect(status().isNoContent());
    mvc.perform(post(BASE + "/" + REPOSITORY_ID + "/remove").with(admin).with(csrf().asHeader()))
        .andExpect(status().isNotFound());

    assertThat(store.all()).extracting(ListSeed::getAddress).doesNotContain(REPOSITORY);
    assertThat(audit())
        .containsExactly(
            "ADD_LIST_SEED " + REPOSITORY + " ADDED - " + REPOSITORY + " RAG tools",
            "ADD_LIST_SEED " + REPOSITORY + " ALREADY_LISTED - " + REPOSITORY + " -",
            "REMOVE_LIST_SEED " + REPOSITORY + " REMOVED " + REPOSITORY + " - stale",
            "REMOVE_LIST_SEED " + REPOSITORY_ID + " NOT_FOUND - - -");
  }

  @Test
  void anAdminAddsListsAndRemovesAWebPage() throws Exception {
    String created =
        mvc.perform(
                post(BASE)
                    .with(admin)
                    .with(csrf().asHeader())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(add("https://API-IT-Blog.acme.dev/best-ai-tools", "\"a roundup\"")))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").value(PAGE_ID))
            .andExpect(jsonPath("$.kind").value("WEB_PAGE"))
            .andExpect(jsonPath("$.address").value(PAGE))
            .andReturn()
            .getResponse()
            .getContentAsString();
    ContractValidator.assertMatches("ListSeedEntry", created);

    String list =
        mvc.perform(get(BASE).with(admin))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    ContractValidator.assertMatches("ListSeeds", list);
    assertThat(list).contains("\"address\":\"" + PAGE + "\"");

    mvc.perform(
            post(BASE)
                .with(admin)
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content(add("https://www.api-it-blog.acme.dev/best-ai-tools/", "null")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("duplicate"));

    mvc.perform(post(BASE + "/" + PAGE_ID + "/remove").with(admin).with(csrf().asHeader()))
        .andExpect(status().isNoContent());

    assertThat(store.all()).extracting(ListSeed::getAddress).doesNotContain(PAGE);
    assertThat(audit())
        .containsExactly(
            "ADD_LIST_SEED " + PAGE + " ADDED - " + PAGE + " a roundup",
            "ADD_LIST_SEED "
                + "https://www.api-it-blog.acme.dev/best-ai-tools/ ALREADY_LISTED - "
                + "https://www.api-it-blog.acme.dev/best-ai-tools/ -",
            "REMOVE_LIST_SEED " + PAGE + " REMOVED " + PAGE + " - -");
  }

  @Test
  void anAddressThatIsNotReadIsRefusedWithTheReasonAndNothingIsWritten() throws Exception {
    refuses("http://api-it-blog.acme.dev/best-ai-tools", "read over https only");
    refuses("https://127.0.0.1/list", "public host name");
    refuses("https://0x7f.0x0.0x0.0x1/list", "public host name");
    refuses("https://wiki.internal/list", "public host name");
    refuses("https://user@api-it-blog.acme.dev/list", "no user info, port or fragment");
    refuses("https://api-it-blog.acme.dev:8443/list", "no user info, port or fragment");
    refuses("https://api-it-blog.acme.dev/list#tools", "no user info, port or fragment");
    refuses("https://github.com/acme/awesome-rag/blob/main/README.md", "a GitHub repository");
    String neverRead =
        refuses(
            "https://www.coursera.org/articles/ai-courses",
            "pages on coursera.org are never read: its terms forbid automated reading without"
                + " written consent.");
    ContractValidator.assertMatches("Problem", neverRead);

    mvc.perform(post(BASE + "/not-an-id/remove").with(admin).with(csrf().asHeader()))
        .andExpect(status().isBadRequest());

    assertThat(audit()).isEmpty();
    assertThat(store.all()).extracting(ListSeed::getAddedBy).doesNotContain(adminId);
  }

  String refuses(String address, String detail) throws Exception {
    String problem =
        mvc.perform(
                post(BASE)
                    .with(admin)
                    .with(csrf().asHeader())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(add(address, "null")))
            .andExpect(status().isBadRequest())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(problem).contains(detail);
    return problem;
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
    mvc.perform(post(BASE + "/" + REPOSITORY_ID + "/remove").with(admin))
        .andExpect(status().isForbidden());

    assertThat(audit()).isEmpty();
    assertThat(store.all()).extracting(ListSeed::getAddress).doesNotContain(REPOSITORY);
  }
}
