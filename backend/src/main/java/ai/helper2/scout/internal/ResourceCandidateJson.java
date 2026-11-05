package ai.helper2.scout.internal;

import ai.helper2.scout.Candidate;
import ai.helper2.scout.usecase.AcceptedCandidate;
import ai.helper2.scout.usecase.CandidateDetails;
import ai.helper2.scout.usecase.InvalidCandidate;
import ai.helper2.shared.ResourceType;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The details of a RESOURCE_CANDIDATE review item (ADR 0051), written when a collector proposes the
 * page and read when an admin accepts it: one place for both, so the two cannot drift apart.
 * Fields: sourceId, url, title, type, tags, and snippet when there is one.
 */
@Component
class ResourceCandidateJson implements CandidateDetails {

  /** The longest title the catalog accepts; a longer one is cut, so the item can be accepted. */
  static final int MAX_TITLE = 300;

  private static final int MAX_SNIPPET = 1000;

  private final ObjectMapper json;

  ResourceCandidateJson(ObjectMapper json) {
    this.json = json;
  }

  String write(String sourceId, Candidate c) {
    Map<String, Object> d = new LinkedHashMap<>();
    d.put("sourceId", sourceId);
    d.put("url", c.getUrl().getValue());
    d.put("title", cut(c.getTitle().strip(), MAX_TITLE));
    d.put("type", c.getType().name());
    d.put("tags", c.getTags().stream().sorted().toList());
    String snippet = c.getSnippet() == null ? null : c.getSnippet().strip();
    if (snippet != null && !snippet.isEmpty()) {
      d.put("snippet", cut(snippet, MAX_SNIPPET));
    }
    return json.writeValueAsString(d);
  }

  @Override
  public AcceptedCandidate read(@Nullable String details) {
    try {
      JsonNode d = json.readTree(details == null ? "" : details);
      JsonNode snippet = d.get("snippet");
      return new AcceptedCandidate(
          text(d, "sourceId"),
          text(d, "url"),
          text(d, "title"),
          snippet != null && snippet.isString() ? snippet.asString() : null,
          type(text(d, "type")),
          tags(d.get("tags")));
    } catch (JacksonException e) {
      throw new InvalidCandidate("the candidate is not JSON");
    }
  }

  private static ResourceType type(String name) {
    try {
      return ResourceType.valueOf(name);
    } catch (IllegalArgumentException e) {
      throw new InvalidCandidate("unknown type " + name);
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
