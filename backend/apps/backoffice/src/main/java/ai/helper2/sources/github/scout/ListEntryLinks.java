package ai.helper2.sources.github.scout;

import ai.helper2.shared.CanonicalUrl;
import ai.helper2.sources.liveweb.scout.CoursePages;
import ai.helper2.sources.liveweb.scout.DiscoveryPages;
import ai.helper2.sources.npm.scout.NpmPackageReader;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

final class ListEntryLinks {

  sealed interface Route permits Model, Paper, Package, Course, Product, Article, Skipped {}

  record Model(String id, CanonicalUrl page) implements Route {}

  record Paper(String id, CanonicalUrl page) implements Route {}

  record Package(String name, CanonicalUrl page) implements Route {}

  record Course(CanonicalUrl page, String platform) implements Route {}

  record Readers(boolean models, boolean papers, boolean packages) {}

  record Product(CanonicalUrl site) implements Route {}

  record Article(CanonicalUrl page) implements Route {}

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
  private final Readers readers;

  ListEntryLinks(ProductLinks productLinks, CoursePages courses, Readers readers) {
    this.productLinks = productLinks;
    this.courses = courses;
    this.readers = readers;
  }

  Route route(AwesomeListParser.Link link, DiscoveryPages pages) {
    ProductLinks.Checked safe = productLinks.page(link.url());
    CanonicalUrl page = safe.site();
    if (page == null) {
      return new Skipped(safe.skip().tag());
    }
    Route confirmable = confirmable(page);
    if (confirmable != null) {
      return confirmable;
    }
    Optional<String> platform =
        courses.platformOfCourse(title(link, page), link.headingPath(), page);
    if (platform.isPresent()) {
      return new Course(page, platform.get());
    }
    ProductLinks.Checked product = productLinks.product(page, pages);
    if (product.site() != null) {
      return new Product(product.site());
    }
    // UC-030 BR-007: a link that reads as an article by the rule of discovery is an article.
    boolean article =
        product.skip() == ProductLinks.Skip.NOT_A_PRODUCT_PAGE && pages.isWritingPage(page);
    return article ? new Article(page) : new Skipped(product.skip().tag());
  }

  private @Nullable Route confirmable(CanonicalUrl page) {
    String model = page.getHuggingFaceModel();
    if (model != null) {
      return readers.models() ? new Model(model, page) : null;
    }
    String paper = page.getArxivPaper();
    if (paper != null) {
      return readers.papers() ? new Paper(paper, page) : null;
    }
    String packageName = NpmPackageReader.nameOf(page);
    return packageName != null && readers.packages() ? new Package(packageName, page) : null;
  }

  @Nullable CanonicalUrl confirmablePage(AwesomeListParser.Link link) {
    CanonicalUrl page = productLinks.page(link.url()).site();
    boolean named =
        page != null
            && (page.getHuggingFaceModel() != null
                || page.getArxivPaper() != null
                || NpmPackageReader.nameOf(page) != null);
    return named ? page : null;
  }

  static String title(AwesomeListParser.Link link, CanonicalUrl page) {
    String words = link.text().strip();
    boolean namesNothing = words.isEmpty() || PLACE_WORDS.contains(words.toLowerCase(Locale.ROOT));
    return namesNothing ? page.getHost() : words;
  }
}
