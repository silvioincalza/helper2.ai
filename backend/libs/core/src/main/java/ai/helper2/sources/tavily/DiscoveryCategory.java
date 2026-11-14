package ai.helper2.sources.tavily;

import ai.helper2.shared.CategoryWords;
import ai.helper2.shared.ResourceType;
import ai.helper2.shared.WebSources;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

public record DiscoveryCategory(
    String id,
    DiscoveryFamily family,
    String shortName,
    String name,
    ResourceType type,
    List<String> queries,
    CategoryWords words,
    Duration interval,
    boolean enabled,
    double minScore,
    boolean requireKeyword,
    boolean indexLists,
    List<String> excludeDomains) {
  public static final int MAX_ID = 30;

  public static final int MAX_SHORT_NAME = 12;

  private static final Pattern TAG = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");

  public static final Set<ResourceType> PRODUCT_TYPES =
      EnumSet.of(
          ResourceType.PRODUCT,
          ResourceType.AGENT,
          ResourceType.PROMPT,
          ResourceType.SKILL,
          ResourceType.MCP_SERVER,
          ResourceType.LIBRARY,
          ResourceType.MODEL,
          ResourceType.DATASET);

  public DiscoveryCategory {
    Objects.requireNonNull(family, "family");
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(interval, "interval");
    Objects.requireNonNull(words, "words");
    require(id != null && TAG.matcher(id).matches(), "id must be lower-case words with hyphens");
    require(id.length() <= MAX_ID, "id must be at most " + MAX_ID + " characters");
    require(
        shortName != null && !shortName.isBlank() && shortName.length() <= MAX_SHORT_NAME,
        "short-name must be 1 to " + MAX_SHORT_NAME + " characters");
    require(name != null && !name.isBlank(), "name is missing");
    require(PRODUCT_TYPES.contains(type), "type " + type + " is not one a product page can have");
    queries = clean(queries);
    require(!queries.isEmpty(), "needs at least one query");
    require(!interval.isNegative(), "interval must be zero or positive");
    require(minScore >= 0 && minScore <= 1, "min-score must be between 0 and 1");
    excludeDomains = clean(excludeDomains).stream().distinct().toList();
  }

  public String sourceId() {
    return WebSources.DISCOVERY_PREFIX + id;
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new IllegalArgumentException(message);
    }
  }

  private static List<String> clean(List<String> values) {
    return values == null
        ? List.of()
        : values.stream()
            .filter(Objects::nonNull)
            .map(String::strip)
            .filter(v -> !v.isEmpty())
            .toList();
  }
}
