package ai.helper2.scout.internal;

import ai.helper2.catalog.ScoutPolicy;
import ai.helper2.scout.Candidate;
import ai.helper2.scout.usecase.AcceptedCandidate;
import ai.helper2.scout.usecase.CandidateDetails;
import ai.helper2.scout.usecase.CandidateEdit;
import ai.helper2.scout.usecase.InvalidCandidate;
import ai.helper2.shared.ResourceType;
import ai.helper2.shared.WebSources;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The details of a RESOURCE_CANDIDATE review item (ADR 0051), written when a collector proposes the
 * page and read when an admin accepts it: one place for both, so the two cannot drift apart.
 *
 * <p>Version 2 fields: {@code version}, {@code sourceId} (the first collector that found it), url,
 * title, type, tags, snippet when there is one, and {@code foundBy}: one entry per collector that
 * found it, with what it knew ({@code {sourceId, discovery}} for a search, {@code {sourceId,
 * awesome: {list, section}}} for an awesome list, ADR 0058). Version 1 (no version, no foundBy) is
 * still read. When a second collector finds an OPEN candidate, {@link #merge} joins it in: the tags
 * are joined, the most specific type is kept, and the collector is added to foundBy.
 *
 * <p>An admin's edit (ADR 0065, {@link #edit}) replaces the title, type or tags and lists them in
 * {@code edited}. A later merge leaves those fields as the admin wrote them, and an edited type
 * need not be one a finder declares.
 */
@Component
class ResourceCandidateJson implements CandidateDetails {

  /** The fields an admin changed (ADR 0065). */
  static final String EDITED = "edited";

  /** The longest title the catalog accepts; a longer one is cut, so the item can be accepted. */
  static final int MAX_TITLE = 300;

  /** Tavily's excerpt as evidence for the enrichment: up to 2,400 characters (ADR 0051). */
  static final int MAX_SNIPPET = ScoutPolicy.PRODUCT_PAGE_EXCERPT_CHARS;

  static final int VERSION = 2;

  /** What a candidate without foundBy (version 1) has. */
  private static final JsonNode NO_FINDERS = JsonNodeFactory.instance.arrayNode();

  private final ObjectMapper json;

  ResourceCandidateJson(ObjectMapper json) {
    this.json = json;
  }

  String write(String sourceId, Candidate c) {
    Map<String, Object> d = new LinkedHashMap<>();
    d.put("version", VERSION);
    d.put("sourceId", sourceId);
    d.put("url", c.getUrl().getValue());
    d.put("title", cut(c.getTitle().strip(), MAX_TITLE));
    d.put("type", c.getType().name());
    d.put("tags", c.getTags().stream().sorted().toList());
    String snippet = c.getSnippet() == null ? null : c.getSnippet().strip();
    if (snippet != null && !snippet.isEmpty()) {
      d.put("snippet", cut(snippet, MAX_SNIPPET));
    }
    d.put("foundBy", List.of(finder(sourceId, c)));
    return json.writeValueAsString(d);
  }

  /**
   * The OPEN candidate in [details] with [c], found by [sourceId], joined in; null when nothing
   * changes (that collector found it before) or [details] cannot be read.
   */
  @Nullable String merge(@Nullable String details, String sourceId, Candidate c) {
    ObjectNode d;
    try {
      JsonNode read = json.readTree(details == null ? "" : details);
      if (!(read instanceof ObjectNode object)) {
        return null;
      }
      d = object;
    } catch (JacksonException e) {
      return null;
    }
    ArrayNode foundBy = foundBy(d);
    for (JsonNode f : foundBy) {
      if (sourceId.equals(f.path("sourceId").asString(null))) {
        return null;
      }
    }
    foundBy.add(json.<JsonNode>valueToTree(finder(sourceId, c)));
    Set<String> edited = edited(d);
    if (!edited.contains("tags")) {
      d.putPOJO("tags", List.copyOf(mergedTags(d, foundBy, c)));
    }
    ResourceType type = typeOrNull(d.path("type").asString(null));
    if (!edited.contains("type")
        && (type == null || c.getType().getSpecificity() < type.getSpecificity())) {
      d.put("type", c.getType().name());
    }
    d.put("version", VERSION);
    return json.writeValueAsString(d);
  }

  /**
   * The joined tags, at most {@link Candidate#MAX_TAGS} so the item can still be accepted: the
   * categories whose search found it first (`discovery-coding` is `coding`), in the order they
   * found it, then the tags it had, then the new finder's other tags. Sorted.
   */
  private static Set<String> mergedTags(JsonNode d, JsonNode foundBy, Candidate c) {
    Set<String> ordered = new LinkedHashSet<>();
    foundBy.forEach(f -> {
      String id = f.path("sourceId").asString("");
      if (id.startsWith(WebSources.DISCOVERY_PREFIX)) {
        ordered.add(id.substring(WebSources.DISCOVERY_PREFIX.length()));
      }
    });
    d.path("tags").forEach(t -> ordered.add(t.asString()));
    ordered.addAll(new TreeSet<>(c.getTags()));
    return ordered.stream()
        .limit(Candidate.MAX_TAGS)
        .collect(Collectors.toCollection(TreeSet::new));
  }

  /**
   * [details] with the admin's [change] (ADR 0065): each given field replaced and listed in {@code
   * edited}. Throws {@link InvalidCandidate} when [details] is not a candidate.
   */
  String edit(@Nullable String details, CandidateEdit change) {
    ObjectNode d;
    try {
      JsonNode read = json.readTree(details == null ? "" : details);
      if (!(read instanceof ObjectNode object)) {
        throw new InvalidCandidate("the candidate is not a JSON object");
      }
      d = object;
    } catch (JacksonException e) {
      throw new InvalidCandidate("the candidate is not JSON");
    }
    Set<String> edited = new TreeSet<>(edited(d));
    if (change.getTitle() != null) {
      d.put("title", change.getTitle());
      edited.add("title");
    }
    if (change.getType() != null) {
      d.put("type", change.getType().name());
      edited.add("type");
    }
    if (change.getTags() != null) {
      d.putPOJO("tags", change.getTags().stream().sorted().toList());
      edited.add("tags");
    }
    d.putPOJO(EDITED, List.copyOf(edited));
    read(json.writeValueAsString(d));
    return json.writeValueAsString(d);
  }

  /** The reason line of a candidate: its type, tags and every collector that found it. */
  String reason(String details) {
    AcceptedCandidate c = read(details);
    String tags = c.getTags().isEmpty() ? "" : " (" + String.join(", ", c.getTags()) + ")";
    return "New "
        + c.getType().name()
        + tags
        + " found by "
        + String.join(", ", finders(details))
        + ": "
        + c.getTitle();
  }

  private static Set<String> edited(JsonNode d) {
    Set<String> fields = new TreeSet<>();
    d.path(EDITED).forEach(f -> fields.add(f.asString("")));
    return fields;
  }

  /** The collectors that found the candidate in [details], first finder first. */
  List<String> finders(@Nullable String details) {
    try {
      return finders(json.readTree(details == null ? "" : details));
    } catch (JacksonException e) {
      return List.of();
    }
  }

  @Override
  public AcceptedCandidate read(@Nullable String details) {
    try {
      JsonNode d = json.readTree(details == null ? "" : details);
      JsonNode snippet = d.get("snippet");
      Set<String> tags = tags(d.get("tags"));
      return new AcceptedCandidate(
          text(d, "sourceId"),
          text(d, "url"),
          text(d, "title"),
          snippet != null && snippet.isString() ? snippet.asString() : null,
          type(text(d, "type")),
          tags,
          facts(d, tags),
          finders(d),
          edited(d).contains("type"));
    } catch (JacksonException e) {
      throw new InvalidCandidate("the candidate is not JSON");
    }
  }

  private static List<String> finders(JsonNode d) {
    List<String> ids = new ArrayList<>();
    foundByOf(d).forEach(f -> ids.add(f.path("sourceId").asString("")));
    if (ids.isEmpty() && d.path("sourceId").isString()) {
      ids.add(d.path("sourceId").asString());
    }
    return ids;
  }

  /**
   * One foundBy entry: the collector and what it knew: what its search said ({@code discovery}), or
   * the awesome list and section it was linked from ({@code awesome}, ADR 0058).
   */
  private static Map<String, Object> finder(String sourceId, Candidate c) {
    Map<String, Object> f = new LinkedHashMap<>();
    f.put("sourceId", sourceId);
    for (String fact : FINDER_FACTS) {
      Object value = c.getFacts().get(fact);
      if (value != null) {
        f.put(fact, value);
      }
    }
    return f;
  }

  /** The facts of a candidate that say how its collector found it. */
  private static final List<String> FINDER_FACTS = List.of("discovery", "awesome");

  /**
   * What the record keeps in its extra when the candidate is accepted: the first finder's search,
   * the categories (its tags) and every finder. Nothing for a version 1 candidate.
   */
  private Map<String, Object> facts(JsonNode d, Set<String> tags) {
    JsonNode foundBy = foundByOf(d);
    if (foundBy.isEmpty()) {
      return Map.of();
    }
    Map<String, Object> facts = new LinkedHashMap<>();
    JsonNode own = foundBy.get(0).get("discovery");
    if (own != null && own.isObject()) {
      facts.put("discovery", json.treeToValue(own, Map.class));
    }
    facts.put("categories", tags.stream().sorted().toList());
    facts.put("foundBy", json.treeToValue(foundBy, List.class));
    Set<String> edited = edited(d);
    if (!edited.isEmpty()) {
      // What an admin changed before accepting (ADR 0065); who did it is in the audit trail.
      facts.put(EDITED, List.copyOf(edited));
    }
    return facts;
  }

  private ArrayNode foundBy(ObjectNode d) {
    JsonNode f = d.get("foundBy");
    if (f instanceof ArrayNode array) {
      return array;
    }
    ArrayNode created = d.putArray("foundBy");
    String first = d.path("sourceId").asString(null);
    if (first != null) {
      created.addObject().put("sourceId", first);
    }
    return created;
  }

  private static JsonNode foundByOf(JsonNode d) {
    JsonNode f = d.get("foundBy");
    return f != null && f.isArray() ? f : NO_FINDERS;
  }

  private static ResourceType type(String name) {
    ResourceType type = typeOrNull(name);
    if (type == null) {
      throw new InvalidCandidate("unknown type " + name);
    }
    return type;
  }

  private static @Nullable ResourceType typeOrNull(@Nullable String name) {
    try {
      return name == null ? null : ResourceType.valueOf(name);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  /** The candidate's tags; none when absent, refused when not a list of strings. */
  private static Set<String> tags(@Nullable JsonNode tags) {
    if (tags == null || tags.isNull()) {
      return Set.of();
    }
    if (!tags.isArray()) {
      throw new InvalidCandidate("tags are not a list");
    }
    Set<String> read = new LinkedHashSet<>();
    for (JsonNode t : tags) {
      if (!t.isString()) {
        throw new InvalidCandidate("a tag is not text");
      }
      read.add(t.asString());
    }
    return read;
  }

  private static String text(@Nullable JsonNode d, String field) {
    JsonNode v = d == null ? null : d.get(field);
    if (v == null || !v.isString() || v.asString().isBlank()) {
      throw new InvalidCandidate(field + " is missing");
    }
    return v.asString();
  }

  private static String cut(String text, int max) {
    return text.length() > max ? text.substring(0, max - 1) + "…" : text;
  }
}
