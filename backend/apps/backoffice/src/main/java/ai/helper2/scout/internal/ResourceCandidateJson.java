package ai.helper2.scout.internal;

import ai.helper2.catalog.CatalogLimits;
import ai.helper2.catalog.ScoutPolicy;
import ai.helper2.scout.Candidate;
import ai.helper2.scout.CandidateSignals;
import ai.helper2.scout.usecase.AcceptedCandidate;
import ai.helper2.scout.usecase.CandidateDetails;
import ai.helper2.scout.usecase.CandidateEdit;
import ai.helper2.scout.usecase.InvalidCandidate;
import ai.helper2.scout.usecase.PackageFacts;
import ai.helper2.shared.ResourceType;
import ai.helper2.shared.WebSources;
import java.time.Instant;
import java.time.format.DateTimeParseException;
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

@Component
class ResourceCandidateJson implements CandidateDetails {
  static final String EDITED = "edited";

  static final int MAX_TITLE = CatalogLimits.TITLE_CHARS;

  static final int MAX_SNIPPET = ScoutPolicy.PRODUCT_PAGE_EXCERPT_CHARS;

  static final int VERSION = 2;

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
    JsonNode fresh = json.readTree(json.writeValueAsString(finder(sourceId, c)));
    for (int i = 0; i < foundBy.size(); i++) {
      if (sourceId.equals(foundBy.get(i).path("sourceId").asString(null))) {
        if (foundBy.get(i).equals(fresh)) {
          return null;
        }
        foundBy.set(i, fresh);
        return json.writeValueAsString(d);
      }
    }
    foundBy.add(fresh);
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
          edited(d).contains("type"),
          signals(d));
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

  private static Map<String, Object> finder(String sourceId, Candidate c) {
    Map<String, Object> f = new LinkedHashMap<>();
    f.put("sourceId", sourceId);
    for (String fact : FINDER_FACTS) {
      Object value = c.getFacts().get(fact);
      if (value != null) {
        f.put(fact, value);
      }
    }
    Map<String, Object> signals = stated(c.getSignals());
    if (!signals.isEmpty()) {
      f.put(SIGNALS, signals);
    }
    return f;
  }

  private static final String SIGNALS = "signals";

  private static Map<String, Object> stated(CandidateSignals s) {
    Map<String, Object> stated = new LinkedHashMap<>();
    if (s.getStars() != null) {
      stated.put("stars", s.getStars());
    }
    if (s.getLastPush() != null) {
      stated.put("lastPush", s.getLastPush().toString());
    }
    if (s.getArchived() != null) {
      stated.put("archived", s.getArchived());
    }
    if (s.getFork() != null) {
      stated.put("fork", s.getFork());
    }
    return stated;
  }

  private static CandidateSignals signals(JsonNode d) {
    JsonNode foundBy = foundByOf(d);
    JsonNode s = foundBy.isEmpty() ? null : foundBy.get(0).get(SIGNALS);
    if (s == null || !s.isObject()) {
      return CandidateSignals.UNKNOWN;
    }
    JsonNode stars = s.path("stars");
    return new CandidateSignals(
        stars.isIntegralNumber() ? stars.asLong() : null,
        instant(s.path("lastPush")),
        flag(s.path("archived")),
        flag(s.path("fork")));
  }

  private static @Nullable Instant instant(JsonNode v) {
    try {
      return v.isString() ? Instant.parse(v.asString()) : null;
    } catch (DateTimeParseException e) {
      return null;
    }
  }

  private static @Nullable Boolean flag(JsonNode v) {
    return v.isBoolean() ? v.asBoolean() : null;
  }

  private static final String PACKAGE = PackageFacts.KEY;

  private static final List<String> FINDER_FACTS = List.of("discovery", "awesome", PACKAGE);

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
    JsonNode registryPackage = foundBy.get(0).get(PACKAGE);
    if (registryPackage != null && registryPackage.isObject()) {
      facts.put(PACKAGE, json.treeToValue(registryPackage, Map.class));
    }
    facts.put("categories", tags.stream().sorted().toList());
    facts.put("foundBy", json.treeToValue(foundBy, List.class));
    Set<String> edited = edited(d);
    if (!edited.isEmpty()) {
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
