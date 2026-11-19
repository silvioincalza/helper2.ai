package ai.helper2.sources.github.scout;

import ai.helper2.shared.CanonicalUrl;
import ai.helper2.sources.liveweb.scout.CoursePages;
import ai.helper2.sources.liveweb.scout.DiscoveryPages;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

final class ListEntryLinks {

  sealed interface Route permits Model, Course, Product, Skipped {}

  record Model(String id, CanonicalUrl page) implements Route {}

  record Course(CanonicalUrl page, String platform) implements Route {}

  record Product(CanonicalUrl site) implements Route {}

  record Skipped(String reason) implements Route {}

  private static final Set<String> PLACE_WORDS =
      Set.of(
          "site",
          "website",
          "web",
          "homepage",
          "home",
          "home page",
          "official site",
          "page",
          "link",
          "here",
          "demo",
          "app");

  private final ProductLinks productLinks;
  private final CoursePages courses;
  private final boolean readsModels;

  ListEntryLinks(ProductLinks productLinks, CoursePages courses, boolean readsModels) {
    this.productLinks = productLinks;
    this.courses = courses;
    this.readsModels = readsModels;
  }

  Route route(AwesomeListParser.Link link, DiscoveryPages pages) {
    ProductLinks.Checked safe = productLinks.page(link.url());
    CanonicalUrl page = safe.site();
    if (page == null) {
      return new Skipped(safe.skip().tag());
    }
    String model = page.getHuggingFaceModel();
    if (model != null && readsModels) {
      return new Model(model, page);
    }
    Optional<String> platform =
        courses.platformOfCourse(title(link, page), link.headingPath(), page);
    if (platform.isPresent()) {
      return new Course(page, platform.get());
    }
    ProductLinks.Checked product = productLinks.product(page, pages);
    return product.site() == null ? new Skipped(product.skip().tag()) : new Product(product.site());
  }

  @Nullable CanonicalUrl modelPage(AwesomeListParser.Link link) {
    CanonicalUrl page = productLinks.page(link.url()).site();
    return page == null || page.getHuggingFaceModel() == null ? null : page;
  }

  static String title(AwesomeListParser.Link link, CanonicalUrl page) {
    String words = link.text().strip();
    boolean namesNothing = words.isEmpty() || PLACE_WORDS.contains(words.toLowerCase(Locale.ROOT));
    return namesNothing ? page.getHost() : words;
  }
}
