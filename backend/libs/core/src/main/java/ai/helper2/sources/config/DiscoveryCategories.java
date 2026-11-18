package ai.helper2.sources.config;

import ai.helper2.platform.config.CategoriesProperties;
import ai.helper2.shared.CategoryWords;
import ai.helper2.sources.tavily.DiscoveryCategory;
import ai.helper2.sources.tavily.DiscoveryFamily;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.core.env.Environment;

public final class DiscoveryCategories {
  private static final Pattern TAG = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");

  private final List<Checked> categories;

  private DiscoveryCategories(List<Checked> categories) {
    this.categories = categories;
  }

  public static DiscoveryCategories bind(Environment env) {
    return of(CategoriesProperties.bind(env));
  }

  public static DiscoveryCategories of(CategoriesProperties properties) {
    Set<String> ids = new HashSet<>();
    List<Checked> checked = new ArrayList<>();
    for (CategoriesProperties.Entry entry : properties.categories()) {
      checked.add(Checked.of(entry));
      if (!ids.add(entry.id())) {
        throw new IllegalArgumentException("helper2.categories: duplicate id " + entry.id());
      }
    }
    return new DiscoveryCategories(List.copyOf(checked));
  }

  public List<String> ids() {
    return categories.stream().map(c -> c.entry().id()).toList();
  }

  public List<DiscoveryCategory> resolve(CollectorProperties.Discovery defaults) {
    List<DiscoveryCategory> resolved = new ArrayList<>();
    for (Checked c : categories) {
      try {
        resolved.add(c.resolve(defaults));
      } catch (IllegalArgumentException | NullPointerException e) {
        throw new IllegalStateException(
            "helper2.categories " + c.entry().id() + ": " + e.getMessage(), e);
      }
    }
    return List.copyOf(resolved);
  }

  private record Checked(
      CategoriesProperties.Entry entry,
      DiscoveryFamily family,
      CategoriesProperties.Discovery discovery) {

    static Checked of(CategoriesProperties.Entry entry) {
      requireTag(entry.id());
      DiscoveryFamily family = family(entry);
      requireShortName(entry);
      return new Checked(entry, family, productDiscovery(entry));
    }

    private static void requireTag(String id) {
      if (id == null || !TAG.matcher(id).matches() || id.length() > DiscoveryCategory.MAX_ID) {
        throw new IllegalArgumentException(
            "helper2.categories: id '"
                + id
                + "' must be lower-case words with hyphens, at most "
                + DiscoveryCategory.MAX_ID
                + " characters");
      }
    }

    private static DiscoveryFamily family(CategoriesProperties.Entry entry) {
      if (entry.family() == null) {
        throw new IllegalArgumentException(
            "helper2.categories " + entry.id() + ": family is missing");
      }
      return Arrays.stream(DiscoveryFamily.values())
          .filter(f -> f.id().equals(entry.family()))
          .findFirst()
          .orElseThrow(() ->
              new IllegalArgumentException(
                  "helper2.categories "
                      + entry.id()
                      + ": family '"
                      + entry.family()
                      + "' is not one of "
                      + Arrays.stream(DiscoveryFamily.values())
                          .map(DiscoveryFamily::id)
                          .collect(Collectors.joining(", "))));
    }

    private static void requireShortName(CategoriesProperties.Entry entry) {
      String shortName = entry.shortName();
      if (shortName == null
          || shortName.isBlank()
          || shortName.length() > DiscoveryCategory.MAX_SHORT_NAME) {
        throw new IllegalArgumentException(
            "helper2.categories "
                + entry.id()
                + ": short-name must be 1 to "
                + DiscoveryCategory.MAX_SHORT_NAME
                + " characters");
      }
    }

    private static CategoriesProperties.Discovery productDiscovery(
        CategoriesProperties.Entry entry) {
      CategoriesProperties.Discovery discovery = entry.discovery();
      if (discovery == null || discovery.type() == null) {
        throw new IllegalArgumentException(
            "helper2.categories " + entry.id() + ": discovery.type is missing");
      }
      if (!DiscoveryCategory.PRODUCT_TYPES.contains(discovery.type())) {
        throw new IllegalArgumentException(
            "helper2.categories "
                + entry.id()
                + ": type "
                + discovery.type()
                + " is not one a product page can have");
      }
      return discovery;
    }

    DiscoveryCategory resolve(CollectorProperties.Discovery defaults) {
      CategoriesProperties.Discovery d = discovery;
      return new DiscoveryCategory(
          entry.id(),
          family,
          entry.shortName(),
          entry.name(),
          d.type(),
          d.queries(),
          CategoryWords.of(entry.words()),
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
}
