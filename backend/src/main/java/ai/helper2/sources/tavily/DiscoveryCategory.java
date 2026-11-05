package ai.helper2.sources.tavily;

import ai.helper2.shared.ResourceType;
import ai.helper2.shared.WebSources;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

/**
 * One category of the AI world (ADR 0051): its own discovery collector, its own dot on the Home
 * radar and its own row on the Sources page. A product its searches find becomes {@code type}; its
 * id is the catalog tag of every result it fits (the ones its searches found, and any result whose
 * title or excerpt has one of its words). The options are already resolved: the interval and the
 * score floor fall back to the global discovery settings, and the excluded domains include the
 * global list.
 *
 * @param requireKeyword keep a result only when its own title or excerpt has one of this category's
 *     words (health, legal: their searches pull general advice pages)
 * @param indexLists false drops list and comparison pages instead of indexing them as articles
 */
public record DiscoveryCategory(
    String id,
    DiscoveryFamily family,
    String shortName,
    String name,
    ResourceType type,
    List<String> queries,
    Keywords words,
    Duration interval,
    boolean enabled,
    double minScore,
    boolean requireKeyword,
    boolean indexLists,
    List<String> excludeDomains) {

  /** The longest category id: "discovery-" plus it fits the source id's 40 characters. */
  public static final int MAX_ID = 30;

  /** The source id's limit on the Home radar (SourceDescriptor.SHORT_NAME_MAX). */
  public static final int MAX_SHORT_NAME = 12;

  /** A tag: lower-case words joined by hyphens ("self-hosted", "model-hosting"). */
  private static final Pattern TAG = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");

  /** Types a product's own site or repository can have; the reading and course types cannot. */
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

  /** The collector's source id: "discovery-coding". */
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

  /**
   * A category's words, each matched at the start of a word in lower case, with hyphens and
   * underscores read as spaces ("fine tuning platform" fits "Fine-Tuning platform"). The patterns
   * are compiled once.
   */
  public static final class Keywords {

    private final List<String> words;
    private final List<Pattern> patterns;

    private Keywords(List<String> words) {
      this.words = words;
      this.patterns =
          words.stream().map(w -> Pattern.compile("\\b" + Pattern.quote(normal(w)))).toList();
    }

    public static Keywords of(List<String> words) {
      return new Keywords(
          clean(words).stream().map(w -> w.toLowerCase(Locale.ROOT)).distinct().toList());
    }

    public List<String> words() {
      return words;
    }

    /** The words found in [text], in the category's order. */
    public List<String> matchedIn(String text) {
      String normal = normal(text);
      return IntStream.range(0, words.size())
          .filter(i -> patterns.get(i).matcher(normal).find())
          .mapToObj(words::get)
          .toList();
    }

    /** Lower case, hyphens and underscores as spaces: "Text-to-Speech" reads "text to speech". */
    static String normal(String text) {
      return text.toLowerCase(Locale.ROOT).replaceAll("[-_]+", " ");
    }

    @Override
    public boolean equals(Object o) {
      return o instanceof Keywords k && k.words.equals(words);
    }

    @Override
    public int hashCode() {
      return words.hashCode();
    }

    @Override
    public String toString() {
      return words.toString();
    }
  }
}
