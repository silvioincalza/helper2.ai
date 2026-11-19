package ai.helper2.site.pages.usecase;

import ai.helper2.catalog.ResourcePathsKt;
import ai.helper2.catalog.usecase.ListIndexableResources;
import ai.helper2.site.pages.Hub;
import ai.helper2.site.pages.IndexPolicy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

public class ListSitemap {

  static final Duration KEEP = Duration.ofHours(1);
  public static final int PART_SIZE = 10_000;
  static final List<String> STATIC_PAGES =
      Stream.concat(
              Stream.of("/", "/sources", "/events", "/mission", "/privacy", "/terms", "/support"),
              Hub.getEntries().stream().map(Hub::getPath))
          .toList();

  private static final String XML = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n";
  private static final String NS = "http://www.sitemaps.org/schemas/sitemap/0.9";
  private static final String PAGES_PART = "pages";
  private static final Pattern RESOURCES_PART = Pattern.compile("resources-([1-9][0-9]{0,4})");

  private final ListIndexableResources resources;
  private final Clock clock;
  private final String base;
  private final int limit;

  private record Part(String name, String xml, @Nullable Instant lastModified) {}

  private record Built(String index, List<Part> parts, Instant at) {}

  private volatile Built built;

  public ListSitemap(
      ListIndexableResources resources, Clock clock, String publicBaseUrl, int limit) {
    this.resources = resources;
    this.clock = clock;
    this.base = publicBaseUrl;
    this.limit = limit;
  }

  public String index() {
    return current().index();
  }

  public Optional<String> part(String name) {
    if (!PAGES_PART.equals(name) && !RESOURCES_PART.matcher(name).matches()) {
      return Optional.empty();
    }
    return current().parts().stream().filter(p -> p.name().equals(name)).map(Part::xml).findFirst();
  }

  public static String partPath(String name) {
    return "/sitemaps/" + name + ".xml";
  }

  private Built current() {
    Built current = built;
    Instant now = clock.instant();
    if (current == null || current.at().plus(KEEP).isBefore(now)) {
      current = build(now);
      built = current;
    }
    return current;
  }

  private Built build(Instant now) {
    List<Part> parts = new ArrayList<>();
    parts.add(new Part(PAGES_PART, staticPages(), null));
    List<ListIndexableResources.Page> pages = indexable();
    for (int from = 0, n = 1; from < pages.size(); from += PART_SIZE, n++) {
      List<ListIndexableResources.Page> chunk =
          pages.subList(from, Math.min(from + PART_SIZE, pages.size()));
      parts.add(new Part("resources-" + n, resourcePages(chunk), chunk.getFirst().lastModified()));
    }
    return new Built(index(parts), List.copyOf(parts), now);
  }

  private List<ListIndexableResources.Page> indexable() {
    List<String> types = IndexPolicy.INSTANCE.getLISTED_TYPES().stream().map(Enum::name).toList();
    return resources.list(types, IndexPolicy.PROMPT_MIN_LENGTH, limit);
  }

  private String index(List<Part> parts) {
    StringBuilder xml = new StringBuilder(XML).append("<sitemapindex xmlns=\"" + NS + "\">\n");
    for (Part part : parts) {
      xml.append("<sitemap><loc>").append(base).append(partPath(part.name())).append("</loc>");
      Instant lastModified = part.lastModified();
      if (lastModified != null) {
        xml.append("<lastmod>").append(day(lastModified)).append("</lastmod>");
      }
      xml.append("</sitemap>\n");
    }
    return xml.append("</sitemapindex>\n").toString();
  }

  private String staticPages() {
    StringBuilder xml = new StringBuilder(XML).append("<urlset xmlns=\"" + NS + "\">\n");
    STATIC_PAGES.forEach(
        path -> xml.append("<url><loc>").append(base).append(path).append("</loc></url>\n"));
    return xml.append("</urlset>\n").toString();
  }

  private String resourcePages(List<ListIndexableResources.Page> chunk) {
    StringBuilder xml = new StringBuilder(XML).append("<urlset xmlns=\"" + NS + "\">\n");
    for (ListIndexableResources.Page page : chunk) {
      xml.append("<url><loc>")
          .append(base)
          .append(ResourcePathsKt.resourcePath(page.id(), page.title()))
          .append("</loc><lastmod>")
          .append(day(page.lastModified()))
          .append("</lastmod></url>\n");
    }
    return xml.append("</urlset>\n").toString();
  }

  private static String day(Instant at) {
    return at.toString().substring(0, 10);
  }
}
