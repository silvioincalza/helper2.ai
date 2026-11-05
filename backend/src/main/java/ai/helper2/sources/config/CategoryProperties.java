package ai.helper2.sources.config;

import ai.helper2.shared.ResourceType;
import ai.helper2.sources.tavily.DiscoveryCategory;
import ai.helper2.sources.tavily.DiscoveryFamily;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * The categories of the AI world, bound from {@code helper2.categories} (ADR 0051): one list entry
 * per category, each its own discovery collector. The entries are read by {@link
 * DiscoveryCollectorRegistrar} before any bean exists, so they are checked here, in code: startup
 * fails on an unknown family or type, an id that is not a tag or longer than 30 characters, a short
 * name over 12 characters, a type a product page cannot have, a category without a query, or two
 * categories with one id.
 */
record CategoryProperties(@Nullable List<Category> categories) {

  /** A tag: lower-case words joined by hyphens. */
  private static final Pattern TAG = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");

  CategoryProperties {
    categories = categories == null ? List.of() : List.copyOf(categories);
    Set<String> ids = new HashSet<>();
    for (Category c : categories) {
      if (!ids.add(c.id())) {
        throw new IllegalArgumentException("helper2.categories: duplicate id " + c.id());
      }
    }
  }

  /** The ids, in the order of the list. */
  List<String> ids() {
    return categories.stream().map(Category::id).toList();
  }

  /**
   * Every category with its options resolved against the shared discovery settings: the interval
   * and the score floor when the category has none, the global excluded domains first.
   */
  List<DiscoveryCategory> resolve(CollectorProperties.Discovery defaults) {
    List<DiscoveryCategory> resolved = new ArrayList<>();
    for (Category c : categories) {
      try {
        resolved.add(c.resolve(defaults));
      } catch (IllegalArgumentException | NullPointerException e) {
        throw new IllegalStateException("helper2.categories " + c.id() + ": " + e.getMessage(), e);
      }
    }
    return List.copyOf(resolved);
  }

  /** One list entry: the category, its words and its discovery block. */
  record Category(
      String id,
      DiscoveryFamily family,
      String shortName,
      String name,
      @Nullable List<String> words,
      Discovery discovery) {

    Category {
      if (id == null || !TAG.matcher(id).matches() || id.length() > DiscoveryCategory.MAX_ID) {
        throw new IllegalArgumentException(
            "helper2.categories: id '"
                + id
                + "' must be lower-case words with hyphens, at most "
                + DiscoveryCategory.MAX_ID
                + " characters");
      }
      if (family == null) {
        throw new IllegalArgumentException("helper2.categories " + id + ": family is missing");
      }
      if (shortName == null
          || shortName.isBlank()
          || shortName.length() > DiscoveryCategory.MAX_SHORT_NAME) {
        throw new IllegalArgumentException(
            "helper2.categories "
                + id
                + ": short-name must be 1 to "
                + DiscoveryCategory.MAX_SHORT_NAME
                + " characters");
      }
      if (discovery == null || discovery.type() == null) {
        throw new IllegalArgumentException(
            "helper2.categories " + id + ": discovery.type is missing");
      }
      if (!DiscoveryCategory.PRODUCT_TYPES.contains(discovery.type())) {
        throw new IllegalArgumentException(
            "helper2.categories "
                + id
                + ": type "
                + discovery.type()
                + " is not one a product page can have");
      }
    }

    DiscoveryCategory resolve(CollectorProperties.Discovery defaults) {
      Discovery d = discovery;
      return new DiscoveryCategory(
          id,
          family,
          shortName,
          name,
          d.type(),
          d.queries(),
          DiscoveryCategory.Keywords.of(words),
          d.interval() == null ? defaults.interval() : d.interval(),
          d.enabled() == null || d.enabled(),
          d.minScore() == null ? defaults.minScore() : d.minScore(),
          d.requireKeyword() != null && d.requireKeyword(),
          d.indexLists() == null || d.indexLists(),
          Stream.concat(
                  defaults.excludeDomains().stream(),
                  d.excludeDomains() == null ? Stream.empty() : d.excludeDomains().stream())
              .toList());
    }
  }

  /** How the category is searched; every option but the type and the queries is optional. */
  record Discovery(
      ResourceType type,
      @Nullable List<String> queries,
      @Nullable Duration interval,
      @Nullable Boolean enabled,
      @Nullable Double minScore,
      @Nullable Boolean requireKeyword,
      @Nullable Boolean indexLists,
      @Nullable List<String> excludeDomains) {}
}
