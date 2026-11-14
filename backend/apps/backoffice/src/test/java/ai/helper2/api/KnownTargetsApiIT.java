package ai.helper2.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.OAuth2LoginRequestPostProcessor;

class KnownTargetsApiIT extends ApiTestBase {

  static final String BASE = "/api/v1/admin/known-targets";

  @Autowired JdbcClient jdbc;

  final String tag = UUID.randomUUID().toString().substring(0, 8);
  final String name = "Chat " + tag;
  final String other = "Chatty " + tag;
  final String adminId = "targets-" + UUID.randomUUID();
  final OAuth2LoginRequestPostProcessor admin =
      oauth2Login()
          .attributes(a -> a.put("sub", adminId))
          .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
  final List<UUID> resources = new ArrayList<>();

  @AfterEach
  void clean() {
    resources.forEach(
        id -> jdbc.sql("DELETE FROM resource WHERE id = :id").param("id", id).update());
    jdbc.sql("DELETE FROM known_target WHERE name LIKE :name").param("name", "%" + tag).update();
  }

  private String write(String kind, String shown, String... others) {
    String list = others.length == 0 ? "" : "\"" + String.join("\",\"", others) + "\"";
    return "{\"kind\":\"%s\",\"name\":\"%s\",\"otherNames\":[%s]}".formatted(kind, shown, list);
  }

  private UUID verified(String title) {
    UUID resource = UUID.randomUUID();
    jdbc.sql(
            """
            INSERT INTO resource (id, canonical_url, url, title, description_origin, type, audience, data_origin)
            VALUES (:id, :url, :url, :title, 'SOURCE_PROVIDED', 'LIBRARY', 'BUILD_WITH_AI', 'INDEXED')
            """)
        .param("id", resource)
        .param("url", "https://known-targets.example/" + resource)
        .param("title", "Uses " + title)
        .update();
    resources.add(resource);
    UUID relationship = UUID.randomUUID();
    jdbc.sql(
            """
            INSERT INTO relationship (id, from_resource_id, to_title, kind, verification, origin, target_kind)
            VALUES (:id, :from, :title, 'INTEGRATES_WITH', 'VERIFIED', 'AI_EXTRACTED', 'SERVICE')
            """)
        .param("id", relationship)
        .param("from", resource)
        .param("title", title)
        .update();
    return relationship;
  }

  private String stateOf(UUID relationship) {
    return jdbc.sql(
            """
            SELECT rel.verification || ' ' || coalesce(rel.target_kind, '-') || ' '
                   || (SELECT count(*) FROM review_item i
                       WHERE i.relationship_id = rel.id AND i.status = 'OPEN'
                         AND i.kind = 'RELATIONSHIP_CANDIDATE')
            FROM relationship rel WHERE rel.id = :id
            """)
        .param("id", relationship)
        .query(String.class)
        .single();
  }

  List<String> audit() {
    return jdbc.sql(
            """
            SELECT action || ' ' || outcome || ' ' || coalesce(before_value, '-') || ' ' || coalesce(after_value, '-')
            FROM admin_audit WHERE admin_id = :a ORDER BY at, id
            """)
        .param("a", adminId)
        .query(String.class)
        .list();
  }

  @Test
  void theListStartsWithTheVendorsAndTheirOtherNames() throws Exception {
    String list =
        mvc.perform(get(BASE).with(admin))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.max").value(1000))
            .andReturn()
            .getResponse()
            .getContentAsString();

    ContractValidator.assertMatches("KnownTargets", list);
    List<String> others =
        JsonPath.read(list, "$.targets[?(@.name == 'Hugging Face')].otherNames[*]");
    assertThat(others).containsExactly("HuggingFace");
    List<String> addedBy = JsonPath.read(list, "$.targets[?(@.name == 'OpenAI')].addedBy");
    assertThat(addedBy).containsExactly("migration");
  }

  @Test
  void anAdminAddsAServiceChangesItsNamesAndRemovesItAndTheRelationshipsFollow() throws Exception {
    String created =
        mvc.perform(
                post(BASE)
                    .with(admin)
                    .with(csrf().asHeader())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(write("SERVICE", "  " + name + " ", other)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.name").value(name))
            .andExpect(jsonPath("$.kind").value("SERVICE"))
            .andExpect(jsonPath("$.otherNames[0]").value(other))
            .andExpect(jsonPath("$.addedBy").value(adminId))
            .andReturn()
            .getResponse()
            .getContentAsString();
    ContractValidator.assertMatches("KnownTargetEntry", created);
    String id = JsonPath.read(created, "$.id");
    UUID byName = verified(name);
    UUID byOtherName = verified(other.toUpperCase() + "'s");

    mvc.perform(get(BASE).with(admin))
        .andExpect(jsonPath("$.targets[?(@.id == '" + id + "')].relationships").value(2));

    mvc.perform(
            post(BASE)
                .with(admin)
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content(write("VENDOR", "Another " + tag, other.toLowerCase())))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("duplicate"));

    mvc.perform(
            put(BASE + "/" + id)
                .with(admin)
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content(write("VENDOR", name)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.kind").value("VENDOR"))
        .andExpect(jsonPath("$.otherNames").isEmpty())
        .andExpect(jsonPath("$.relationships").value(1));
    assertThat(stateOf(byName)).isEqualTo("VERIFIED VENDOR 0");
    assertThat(stateOf(byOtherName)).isEqualTo("UNVERIFIED - 1");

    mvc.perform(post(BASE + "/" + id + "/remove").with(admin).with(csrf().asHeader()))
        .andExpect(status().isNoContent());
    mvc.perform(post(BASE + "/" + id + "/remove").with(admin).with(csrf().asHeader()))
        .andExpect(status().isNotFound());
    assertThat(stateOf(byName)).isEqualTo("UNVERIFIED - 1");

    assertThat(audit())
        .containsExactly(
            "ADD_KNOWN_TARGET ADDED - SERVICE " + name,
            "ADD_KNOWN_TARGET TAKEN - -",
            "EDIT_KNOWN_TARGET EDITED RELEASED 1 SERVICE " + name + " VENDOR " + name,
            "REMOVE_KNOWN_TARGET REMOVED RELEASED 1 VENDOR " + name + " -",
            "REMOVE_KNOWN_TARGET NOT_FOUND - -");
  }

  @Test
  void aNameMustBeOnePlainLineOfTwoToSixtyCharacters() throws Exception {
    for (String bad : List.of("x", "a".repeat(61), "two\\nlines", "'s")) {
      String problem =
          mvc.perform(
                  post(BASE)
                      .with(admin)
                      .with(csrf().asHeader())
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(write("SERVICE", bad)))
              .andExpect(status().isBadRequest())
              .andReturn()
              .getResponse()
              .getContentAsString();
      ContractValidator.assertMatches("Problem", problem);
    }
  }

  @Test
  void onlyAdminsReadOrChangeTheListAndOnlyWithTheCsrfToken() throws Exception {
    String body = write("SERVICE", name);
    mvc.perform(get(BASE)).andExpect(status().isUnauthorized());
    mvc.perform(get(BASE).with(oauth2Login())).andExpect(status().isForbidden());
    mvc.perform(
            post(BASE)
                .with(oauth2Login())
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isForbidden());
    mvc.perform(post(BASE).with(admin).contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isForbidden());
    mvc.perform(
            post(BASE + "/" + UUID.randomUUID() + "/remove")
                .with(oauth2Login())
                .with(csrf().asHeader()))
        .andExpect(status().isForbidden());
  }
}
