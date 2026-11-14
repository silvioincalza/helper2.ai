package ai.helper2.examples.internal;

import ai.helper2.catalog.ReviewQueue;
import ai.helper2.examples.DraftQueue;
import ai.helper2.examples.ExampleDraft;
import ai.helper2.examples.WaitingDraft;
import ai.helper2.shared.AudiencePath;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class ReviewQueueDrafts implements DraftQueue {

  private final ReviewQueue queue;
  private final ObjectMapper json;

  ReviewQueueDrafts(ReviewQueue queue, ObjectMapper json) {
    this.queue = queue;
    this.json = json;
  }

  @Override
  public void open(ExampleDraft draft) {
    queue.open(
        ReviewQueue.Kind.EXAMPLE_CANDIDATE,
        draft.getResourceId(),
        "Drafted example: " + draft.getInput().getLabel(),
        json.writeValueAsString(
            Map.of(
                "label", draft.getInput().getLabel(),
                "query", draft.getInput().getQuery(),
                "path", draft.getInput().getPath().name(),
                "model", draft.getModel())),
        ReviewQueue.Links.NONE);
  }

  @Override
  public Set<UUID> waitingResources() {
    return items().stream()
        .map(ReviewQueue.Item::resourceId)
        .filter(Objects::nonNull)
        .collect(Collectors.toSet());
  }

  @Override
  public List<WaitingDraft> waiting() {
    return items().stream().map(i -> read(i.details())).filter(Objects::nonNull).toList();
  }

  @Override
  public @Nullable WaitingDraft read(@Nullable String details) {
    try {
      JsonNode d = json.readTree(details == null ? "" : details);
      return new WaitingDraft(
          text(d, "label"),
          text(d, "query"),
          AudiencePath.valueOf(text(d, "path")),
          text(d, "model"));
    } catch (JacksonException | IllegalArgumentException e) {
      return null;
    }
  }

  private List<ReviewQueue.Item> items() {
    return queue.openItems(ReviewQueue.Kind.EXAMPLE_CANDIDATE);
  }

  private static String text(JsonNode d, String field) {
    JsonNode v = d == null ? null : d.get(field);
    if (v == null || !v.isString() || v.asString().isBlank()) {
      throw new IllegalArgumentException(field);
    }
    return v.asString();
  }
}
