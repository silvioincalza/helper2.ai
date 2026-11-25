package ai.searchavo.research.internal;

import ai.searchavo.catalog.ReviewQueue;
import ai.searchavo.research.ResearchItems;
import ai.searchavo.research.ResearchRuns;
import ai.searchavo.research.ResearchSubject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ReviewQueueResearchItems implements ResearchItems {

  static final Set<ReviewQueue.Kind> RESEARCHABLE =
      Set.of(ReviewQueue.Kind.RESOURCE_CANDIDATE, ReviewQueue.Kind.COURSE_CANDIDATE);

  private final ReviewQueue queue;
  private final ResearchRuns runs;
  private final JsonMapper json;

  ReviewQueueResearchItems(ReviewQueue queue, ResearchRuns runs, JsonMapper json) {
    this.queue = queue;
    this.runs = runs;
    this.json = json;
  }

  @Override
  public Optional<ResearchSubject> subject(UUID itemId) {
    return queue
        .find(itemId)
        .filter(i -> i.status() == ReviewQueue.Status.OPEN && RESEARCHABLE.contains(i.kind()))
        .map(this::subject);
  }

  @Override
  public List<UUID> openNotResearched(int limit) {
    Set<UUID> active = runs.activeItems();
    Set<UUID> done = runs.doneItems();
    return Stream.of(ReviewQueue.Kind.RESOURCE_CANDIDATE, ReviewQueue.Kind.COURSE_CANDIDATE)
        .flatMap(k -> queue.openItems(k).stream())
        .map(ReviewQueue.Item::id)
        .filter(id -> !active.contains(id) && !done.contains(id))
        .limit(limit)
        .toList();
  }

  @Override
  public int openResearchedDone() {
    Set<UUID> active = runs.activeItems();
    Set<UUID> done = runs.doneItems();
    return (int)
        RESEARCHABLE.stream()
            .flatMap(k -> queue.openItems(k).stream())
            .map(ReviewQueue.Item::id)
            .filter(id -> done.contains(id) && !active.contains(id))
            .count();
  }

  ResearchSubject subject(ReviewQueue.Item item) {
    String title = afterColon(item.reason());
    if (item.kind() == ReviewQueue.Kind.COURSE_CANDIDATE
        && (item.details() == null || !item.details().strip().startsWith("{"))) {
      return new ResearchSubject(
          item.id(),
          item.kind().name(),
          line(item.details(), "URL: "),
          title,
          "COURSE",
          List.of(),
          line(item.details(), "Snippet: "));
    }
    JsonNode d = item.details() == null ? null : json.readTree(item.details());
    List<String> tags = new ArrayList<>();
    if (d != null && d.path("tags").isArray()) {
      d.path("tags").forEach(t -> tags.add(t.asString()));
    }
    return new ResearchSubject(
        item.id(),
        item.kind().name(),
        text(d, "url", ""),
        text(d, "title", title),
        text(d, "type", "OTHER"),
        List.copyOf(tags),
        d == null || !d.path("snippet").isString() ? null : d.path("snippet").asString(),
        confirmed(d));
  }

  private static Map<String, String> confirmed(@Nullable JsonNode d) {
    Map<String, String> values = new LinkedHashMap<>();
    if (d != null && d.path("confirmed").isObject()) {
      d.path("confirmed")
          .properties()
          .forEach(e -> {
            if (e.getValue().isValueNode() && !e.getValue().isNull()) {
              values.put(e.getKey(), e.getValue().asString(""));
            }
          });
    }
    return values;
  }

  private static String text(@Nullable JsonNode d, String field, String fallback) {
    if (d == null || !d.path(field).isString() || d.path(field).asString().isBlank()) {
      return fallback;
    }
    return d.path(field).asString();
  }

  private static String line(@Nullable String text, String prefix) {
    if (text == null) {
      return "";
    }
    return text.lines()
        .filter(l -> l.startsWith(prefix))
        .map(l -> l.substring(prefix.length()).strip())
        .findFirst()
        .orElse("");
  }

  private static String afterColon(String reason) {
    int at = reason.indexOf(": ");
    return at < 0 ? reason : reason.substring(at + 2);
  }
}
