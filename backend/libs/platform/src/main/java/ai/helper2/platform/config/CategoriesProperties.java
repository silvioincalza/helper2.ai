package ai.helper2.platform.config;

import ai.helper2.shared.Category;
import ai.helper2.shared.CategoryCatalog;
import ai.helper2.shared.CategoryWords;
import ai.helper2.shared.ResourceType;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The categories of the AI world as search and the catalog read them (ADR 0013), from the same
 * {@code helper2.categories} list the discovery collectors are registered from (ADR 0022). Only
 * what a match and a filter need is bound here: the id, the names, the family, the words, the
 * discovery type and require-keyword. The collectors' own checks of the list stay in the sources
 * module; this one fails startup on a bad id or two categories with one id ({@link
 * CategoryCatalog}).
 */
@ConfigurationProperties("helper2")
public record CategoriesProperties(@Nullable List<Entry> categories) {

  public CategoriesProperties {
    categories = categories == null ? List.of() : List.copyOf(categories);
  }

  /** The categories in the configured order. */
  public CategoryCatalog catalog() {
    return new CategoryCatalog(categories.stream().map(Entry::category).toList());
  }

  /** One list entry; the discovery block's other options are the collectors'. */
  public record Entry(
      String id,
      String family,
      String shortName,
      String name,
      @Nullable List<String> words,
      @Nullable Discovery discovery) {

    Category category() {
      return new Category(
          id,
          name,
          shortName,
          family,
          CategoryWords.of(words),
          discovery != null && Boolean.TRUE.equals(discovery.requireKeyword()),
          discovery == null ? null : discovery.type());
    }
  }

  /** How many web searches the discovery collectors run in all, as the Privacy page states it. */
  public int discoverySearches() {
    return categories.stream()
        .map(Entry::discovery)
        .filter(d -> d != null && d.queries() != null)
        .mapToInt(d -> d.queries().size())
        .sum();
  }

  /** What a match and the policy need from the discovery block. */
  public record Discovery(
      @Nullable ResourceType type,
      @Nullable Boolean requireKeyword,
      @Nullable List<String> queries) {}
}
