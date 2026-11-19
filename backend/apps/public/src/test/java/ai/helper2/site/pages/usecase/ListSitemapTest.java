package ai.helper2.site.pages.usecase;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.catalog.Resources;
import ai.helper2.catalog.usecase.ListIndexableResources;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class ListSitemapTest {

  private static final Instant NOW = Instant.parse("2026-11-19T10:00:00Z");

  private static ListSitemap sitemap(int resources) {
    List<ListIndexableResources.Page> pages =
        IntStream.range(0, resources)
            .mapToObj(i ->
                new ListIndexableResources.Page(
                    UUID.nameUUIDFromBytes(("r" + i).getBytes()),
                    "Tool " + i,
                    NOW.minusSeconds(60L * i)))
            .toList();
    ListIndexableResources list =
        new ListIndexableResources((Resources) null) {
          @Override
          public List<Page> list(java.util.Collection<String> types, int promptMin, int limit) {
            return pages.subList(0, Math.min(limit, pages.size()));
          }
        };
    return new ListSitemap(list, Clock.fixed(NOW, ZoneOffset.UTC), "https://helper2.ai", 200_000);
  }

  @Test
  void theSitemapIsAnIndexOfParts() {
    String index = sitemap(ListSitemap.PART_SIZE * 2 + 1).index();
    assertThat(index)
        .startsWith("<?xml")
        .contains("<sitemapindex xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">")
        .contains("<loc>https://helper2.ai/sitemaps/pages.xml</loc></sitemap>")
        .contains("<loc>https://helper2.ai/sitemaps/resources-1.xml</loc><lastmod>2026-11-19")
        .contains("<loc>https://helper2.ai/sitemaps/resources-3.xml</loc>")
        .doesNotContain("resources-4");
  }

  @Test
  void eachResourcePartHoldsAtMostOnePartSize() {
    ListSitemap sitemap = sitemap(ListSitemap.PART_SIZE + 5);
    String first = sitemap.part("resources-1").orElseThrow();
    String second = sitemap.part("resources-2").orElseThrow();
    assertThat(first.split("<url>", -1)).hasSize(ListSitemap.PART_SIZE + 1);
    assertThat(second.split("<url>", -1)).hasSize(6);
    assertThat(first).contains("/resource/").contains("/tool-0</loc><lastmod>2026-11-19</lastmod>");
  }

  @Test
  void thePagesPartListsTheSitePages() {
    assertThat(sitemap(0).part("pages").orElseThrow())
        .contains("<loc>https://helper2.ai/</loc>")
        .contains("<loc>https://helper2.ai/support</loc>")
        .contains("<loc>https://helper2.ai/mcp-servers</loc>")
        .contains("<loc>https://helper2.ai/courses</loc>");
    assertThat(sitemap(0).index()).doesNotContain("resources-1");
  }

  @Test
  void anUnknownPartIsNotFound() {
    ListSitemap sitemap = sitemap(3);
    assertThat(sitemap.part("resources-2")).isEmpty();
    assertThat(sitemap.part("resources-0")).isEmpty();
    assertThat(sitemap.part("../etc")).isEmpty();
    assertThat(sitemap.part("")).isEmpty();
  }
}
