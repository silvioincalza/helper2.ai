package ai.helper2.platform.config;

import ai.helper2.shared.Category;
import ai.helper2.shared.CategoryCatalog;
import ai.helper2.shared.CategoryWords;
import ai.helper2.shared.ResourceType;
import java.time.Duration;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;

@ConfigurationProperties(CategoriesProperties.PREFIX)
public record CategoriesProperties(@Nullable List<Entry> categories) {

  static final String PREFIX = "helper2";

  public CategoriesProperties {
    categories = categories == null ? List.of() : List.copyOf(categories);
  }

  public static CategoriesProperties bind(Environment env) {
    return Binder.get(env)
        .bind(PREFIX, Bindable.of(CategoriesProperties.class))
        .orElseGet(() -> new CategoriesProperties(List.of()));
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
      @Nullable List<String> queries,
      @Nullable Duration interval,
      @Nullable Boolean enabled,
      @Nullable Double minScore,
      @Nullable Boolean requireKeyword,
      @Nullable Boolean indexLists,
      @Nullable List<String> excludeDomains) {}
}
