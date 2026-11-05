package ai.helper2.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;

/** Pages for crawlers (ADR 0050): the head, the status, the sitemap and robots.txt. */
@TestPropertySource(properties = "helper2.pages.template=classpath:/pages/index.html")
class PagesIT extends ApiTestBase {

  private static final Pattern SITEMAP_RESOURCE =
      Pattern.compile("<loc>https://helper2\\.ai/resource/([0-9a-f-]{36})</loc>");

  @Autowired JdbcClient jdbc;

  private UUID curated(String canonicalUrl) {
    return jdbc.sql("SELECT id FROM resource WHERE canonical_url = :u")
        .param("u", canonicalUrl)
        .query(UUID.class)
        .single();
  }

  private String page(String path, int status) throws Exception {
    return mvc.perform(get(path))
        .andExpect(status().is(status))
        .andExpect(header().string("Cache-Control", "no-cache"))
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  @Test
  void aResourcePageCarriesItsHeadAndBody() throws Exception {
    UUID id = curated("https://deepl.com/translator");
    String html = page("/resource/" + id, 200);
    assertThat(html)
        .contains("<title data-h2-head>DeepL")
        .contains("rel=\"canonical\" href=\"https://helper2.ai/resource/" + id + "\"")
        .contains("application/ld+json")
        .contains("<div id=\"root\"><main class=\"h2-prerender\"><h1>DeepL")
        .contains("<script type=\"module\"");
  }

  @Test
  void unknownAddressesAreRealNotFoundsThatStillLoadTheApp() throws Exception {
    for (String path :
        List.of(
            "/resource/" + UUID.randomUUID(),
            "/resource/not-an-id",
            "/no-such-page",
            "/sources/")) {
      assertThat(page(path, 404))
          .as(path)
          .contains("<meta data-h2-head name=\"robots\" content=\"noindex\">")
          .contains("<div id=\"root\">");
    }
  }

  @Test
  void searchAndPersonalPagesAreNotIndexedButFollowed() throws Exception {
    for (String path : List.of("/search?q=ollama", "/?q=ollama", "/saved", "/account")) {
      assertThat(page(path, 200)).as(path).contains("content=\"noindex, follow\"");
    }
    assertThat(page("/", 200)).contains("content=\"index, follow\"").contains("\"WebSite\"");
  }

  @Test
  void filesAndTheApiAreNotClaimed() throws Exception {
    mvc.perform(get("/assets/missing.js")).andExpect(status().isNotFound());
    mvc.perform(get("/api/v1/home/examples")).andExpect(status().isOk());
  }

  @Test
  void robotsNamesTheSitemapAndNeverBlocksTheApi() throws Exception {
    mvc.perform(get("/robots.txt"))
        .andExpect(status().isOk())
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.containsString(
                        "Sitemap: https://helper2.ai/sitemap.xml")))
        .andExpect(
            content()
                .string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("/api"))));
  }

  @Test
  void theSitemapListsOnlyPagesThePageItselfMarksIndexed() throws Exception {
    String xml =
        mvc.perform(get("/sitemap.xml"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(xml).contains("<loc>https://helper2.ai/sources</loc>");
    List<String> ids =
        SITEMAP_RESOURCE.matcher(xml).results().map(m -> m.group(1)).limit(25).toList();
    assertThat(ids).isNotEmpty();
    for (String id : ids) {
      assertThat(page("/resource/" + id, 200))
          .as("sitemap resource %s", id)
          .contains("content=\"index, follow\"");
    }
    List<UUID> news =
        jdbc.sql("SELECT id FROM resource WHERE type IN ('NEWS', 'ARTICLE')")
            .query(UUID.class)
            .list();
    news.forEach(n -> assertThat(xml).doesNotContain(n.toString()));
  }
}
