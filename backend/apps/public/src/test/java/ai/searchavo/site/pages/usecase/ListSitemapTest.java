package ai.searchavo.site.pages.usecase;

import static org.assertj.core.api.Assertions.assertThat;

import ai.searchavo.catalog.Resources;
import ai.searchavo.catalog.usecase.ListIndexableResources;
import ai.searchavo.shared.ResourceType;
import ai.searchavo.site.pages.Related;
import ai.searchavo.site.pages.SiteLists;
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
    return new ListSitemap(
        list,
        FILLED_EXCEPT_COURSES,
        Clock.fixed(NOW, ZoneOffset.UTC),
        "https://searchavo.ai",
        200_000);
  }

  private static final SiteLists FILLED_EXCEPT_COURSES =
      new SiteLists() {
        @Override
        public List<Related> newInCatalog() {
          return List.of();
        }

        @Override
        public List<Related> upcomingEvents() {
          return List.of();
        }

        @Override
        public List<Related> hub(ResourceType type) {
          return type == ResourceType.COURSE
              ? List.of()
              : List.of(new Related(UUID.nameUUIDFromBytes(type.name().getBytes()), "One"));
        }
      };

  @Test
  void theSitemapIsAnIndexOfParts() {
    String index = sitemap(ListSitemap.PART_SIZE * 2 + 1).index();
    assertThat(index)
        .startsWith("<?xml")
        .contains("<sitemapindex xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">")
        .contains("<loc>https://searchavo.ai/sitemaps/pages.xml</loc></sitemap>")
        .contains("<loc>https://searchavo.ai/sitemaps/resources-1.xml</loc><lastmod>2026-11-19")
        .contains("<loc>https://searchavo.ai/sitemaps/resources-3.xml</loc>")
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
        .contains("<loc>https://searchavo.ai/</loc>")
        .contains("<loc>https://searchavo.ai/support</loc>")
        .contains("<loc>https://searchavo.ai/mcp-servers</loc>")
        .contains("<loc>https://searchavo.ai/prompts</loc>");
    assertThat(sitemap(0).index()).doesNotContain("resources-1");
  }

  @Test
  void aHubWithNoResourceIsNotListed() {
    assertThat(sitemap(0).part("pages").orElseThrow()).doesNotContain("/courses");
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
