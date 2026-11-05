package ai.helper2.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.helper2.shared.ResourceType;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/** Categories as a search filter (ADR 0053): the list, the filter, the facet and the backfill. */
class CategoriesApiIT extends ApiTestBase {

  @Autowired JdbcClient jdbc;

  private static String unique(String base) {
    return base + " " + UUID.randomUUID().toString().substring(0, 8);
  }

  @Test
  void theCategoriesComeInTheConfiguredOrderAndAreCachedByTheirETag() throws Exception {
    String body =
        mvc.perform(get("/api/v1/categories"))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "max-age=3600, public"))
            .andExpect(header().exists("ETag"))
            .andExpect(jsonPath("$.length()").value(34))
            .andExpect(jsonPath("$[0].id").value("prompt-libraries"))
            .andExpect(jsonPath("$[0].family").value("prompts-and-skills"))
            .andExpect(jsonPath("$[0].shortName").value("Prompts"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    ContractValidator.assertMatches("Category", body.substring(1, body.indexOf('}') + 1));
    String etag =
        mvc.perform(get("/api/v1/categories")).andReturn().getResponse().getHeader("ETag");

    mvc.perform(get("/api/v1/categories").header("If-None-Match", etag))
        .andExpect(status().isNotModified());
  }

  @Test
  void aLiveResultIsFilteredAndCountedByTheCategoryItGetsWhenMerged() throws Exception {
    String q = unique("category filter");
    FAKE.set(x ->
        List.of(
            fakeHit(
                "https://github.com/acme/coder-" + UUID.randomUUID(),
                "Acme coding assistant",
                "An AI coding assistant for your editor",
                ResourceType.LIBRARY,
                null,
                null,
                null,
                0),
            fakeHit(
                "https://github.com/acme/other-" + UUID.randomUUID(),
                "Acme other",
                "An AI library",
                ResourceType.LIBRARY,
                null,
                null,
                null,
                1)));

    String body =
        mvc.perform(
                get("/api/v1/search")
                    .param("q", q)
                    .param("source", "fake-live")
                    .param("category", "coding"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(1))
            .andExpect(jsonPath("$.results[0].title").value("Acme coding assistant"))
            .andExpect(jsonPath("$.results[0].categories[0]").value("coding"))
            .andExpect(jsonPath("$.facets.category[?(@.value=='coding')].count").value(1))
            .andExpect(jsonPath("$.facets.category[?(@.value=='unknown')].count").value(1))
            .andReturn()
            .getResponse()
            .getContentAsString();
    ContractValidator.assertMatches("SearchResponse", body);
  }

  @Test
  void anUnknownCategoryIsABadRequestWithProblemDetails() throws Exception {
    mvc.perform(get("/api/v1/search").param("q", "x").param("category", "not-a-category"))
        .andExpect(status().isBadRequest())
        .andExpect(header().string("Content-Type", "application/problem+json"))
        .andExpect(jsonPath("$.status").value(400));
    mvc.perform(get("/api/v1/search").param("q", "x").param("category", "unknown"))
        .andExpect(status().isOk());
  }

  @Test
  void theGuideRefusesAnUnknownCategoryBeforeAnyModelWork() throws Exception {
    mvc.perform(
            post("/api/v1/guide/stream")
                .with(csrf())
                .contentType("application/json")
                .content(
                    """
                    {"request": "a tool to review code", "categories": ["not-a-category"]}
                    """))
        .andExpect(status().isBadRequest());
  }

  @Test
  void anAdminStartsTheBackfillAndItIsAudited() throws Exception {
    String adminId = "categories-" + UUID.randomUUID();
    mvc.perform(
            post("/api/v1/admin/category-backfill")
                .with(csrf())
                .with(
                    oauth2Login()
                        .attributes(a -> a.put("sub", adminId))
                        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
        .andExpect(status().isAccepted());

    assertThat(
            jdbc.sql(
                    "SELECT outcome FROM admin_audit WHERE admin_id = :a AND action = 'BACKFILL_CATEGORIES'")
                .param("a", adminId)
                .query(String.class)
                .list())
        .containsExactly("STARTED");
    // The backfill runs in the background: it leaves its marker when it is done.
    long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
    while (markers() == 0 && System.nanoTime() < deadline) {
      Thread.sleep(100);
    }
    assertThat(markers()).isEqualTo(1);
  }

  private int markers() {
    return jdbc.sql("SELECT count(*) FROM catalog_marker WHERE name = 'categories'")
        .query(Integer.class)
        .single();
  }

  @Test
  void aGuestCannotStartTheBackfill() throws Exception {
    mvc.perform(post("/api/v1/admin/category-backfill").with(csrf()))
        .andExpect(status().isUnauthorized());
  }
}
