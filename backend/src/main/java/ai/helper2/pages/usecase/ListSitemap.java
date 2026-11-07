package ai.helper2.pages.usecase;

import ai.helper2.catalog.usecase.ListIndexableResources;
import ai.helper2.pages.IndexPolicy;
import ai.helper2.pages.ResourcePathsKt;
import ai.helper2.pages.config.PagesProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * The sitemap (ADR 0050 §6): the indexed static pages, then every index-worthy resource page with
 * the day it last changed. Built at most once an hour and kept in memory: a crawler asking often
 * costs one query an hour.
 */
@Service
public class ListSitemap {

  static final Duration KEEP = Duration.ofHours(1);
  private static final List<String> STATIC_PAGES =
      List.of("/", "/sources", "/events", "/mission", "/privacy", "/terms", "/support");

  private final ListIndexableResources resources;
  private final Clock clock;
  private final String base;
  private final int limit;

  private record Built(String xml, Instant at) {}

  private volatile Built built;

  public ListSitemap(ListIndexableResources resources, Clock clock, PagesProperties properties) {
    this.resources = resources;
    this.clock = clock;
    this.base = properties.publicBaseUrl();
    this.limit = properties.sitemapLimit();
  }

  public String xml() {
    Built current = built;
    Instant now = clock.instant();
    if (current == null || current.at().plus(KEEP).isBefore(now)) {
      current = new Built(build(), now);
      built = current;
    }
    return current.xml();
  }

  private String build() {
    StringBuilder xml =
        new StringBuilder(
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">\n");
    STATIC_PAGES.forEach(
        path -> xml.append("<url><loc>").append(base).append(path).append("</loc></url>\n"));
    List<String> types = IndexPolicy.INSTANCE.getINDEXED_TYPES().stream().map(Enum::name).toList();
    for (ListIndexableResources.Page page :
        resources.list(types, IndexPolicy.PROMPT_MIN_LENGTH, limit - STATIC_PAGES.size())) {
      xml.append("<url><loc>")
          .append(base)
          .append(ResourcePathsKt.resourcePath(page.id(), page.title()))
          .append("</loc><lastmod>")
          .append(page.lastModified().truncatedTo(ChronoUnit.DAYS).toString(), 0, 10)
          .append("</lastmod></url>\n");
    }
    return xml.append("</urlset>\n").toString();
  }
}
