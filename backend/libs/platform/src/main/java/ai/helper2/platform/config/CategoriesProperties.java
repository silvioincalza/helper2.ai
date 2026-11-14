package ai.helper2.platform.config;

import ai.helper2.shared.Category;
import ai.helper2.shared.CategoryCatalog;
import ai.helper2.shared.CategoryWords;
import ai.helper2.shared.ResourceType;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("helper2")
public record CategoriesProperties(@Nullable List<Entry> categories) {

  public CategoriesProperties {
    categories = categories == null ? List.of() : List.copyOf(categories);
  }

  public CategoryCatalog catalog() {
    return new CategoryCatalog(categories.stream().map(Entry::category).toList());
  }

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

  public int discoverySearches() {
    return categories.stream()
        .map(Entry::discovery)
        .filter(d -> d != null && d.queries() != null)
        .mapToInt(d -> d.queries().size())
        .sum();
  }

  public record Discovery(
      @Nullable ResourceType type,
      @Nullable Boolean requireKeyword,
      @Nullable List<String> queries) {}
}
