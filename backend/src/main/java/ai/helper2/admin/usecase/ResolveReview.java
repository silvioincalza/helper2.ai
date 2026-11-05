package ai.helper2.admin.usecase;

import ai.helper2.catalog.ReviewQueue;
import ai.helper2.catalog.usecase.ResolveReviewItem;
import ai.helper2.examples.ExampleOrigin;
import ai.helper2.examples.usecase.AddHomeExample;
import ai.helper2.platform.ApiProblem;
import ai.helper2.platform.ProblemType;
import ai.helper2.shared.AudiencePath;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * An administrator decides a review item. Accepting a drafted example (EXAMPLE_CANDIDATE, ADR 0049
 * §6) first adds it, AI_DRAFTED with its model, then records the decision, in one transaction. A
 * refused example (a duplicate, a rule, an unreadable draft) is an {@link ApiProblem}: the item
 * stays open, and the refused add stays in the audit trail (no rollback for it).
 */
@Service
public class ResolveReview {

  private final ReviewQueue queue;
  private final ResolveReviewItem resolve;
  private final AddHomeExample addExample;
  private final ObjectMapper json;

  public ResolveReview(
      ReviewQueue queue, ResolveReviewItem resolve, AddHomeExample addExample, ObjectMapper json) {
    this.queue = queue;
    this.resolve = resolve;
    this.addExample = addExample;
    this.json = json;
  }

  @Transactional(noRollbackFor = ApiProblem.class)
  public ResolveReviewItem.Outcome resolve(
      UUID id, ReviewQueue.Status decision, String adminId, @Nullable String note) {
    Optional<ReviewQueue.Item> draft =
        queue
            .find(id)
            .filter(i -> i.kind() == ReviewQueue.Kind.EXAMPLE_CANDIDATE)
            .filter(i -> i.status() == ReviewQueue.Status.OPEN)
            .filter(i -> decision == ReviewQueue.Status.ACCEPTED);
    draft.ifPresent(item -> addDrafted(item, adminId));
    ResolveReviewItem.Outcome outcome = resolve.resolve(id, decision, adminId, note);
    if (draft.isPresent() && !(outcome instanceof ResolveReviewItem.Outcome.Resolved)) {
      // Decided by someone else while the example was added: undo the add with the decision.
      throw new IllegalStateException("review item " + id + " was decided concurrently");
    }
    return outcome;
  }

  private void addDrafted(ReviewQueue.Item item, String adminId) {
    Draft draft = read(item.details());
    switch (addExample.add(
        adminId,
        draft.label(),
        draft.query(),
        draft.path(),
        List.of(item.resourceId()),
        ExampleOrigin.AI_DRAFTED,
        draft.model())) {
      case AddHomeExample.Outcome.Added a -> {}
      case AddHomeExample.Outcome.Duplicate d ->
          throw new ApiProblem(
              ProblemType.DUPLICATE,
              "Another example already has this label or this query. Reject the draft, or change"
                  + " the example first.");
      case AddHomeExample.Outcome.Invalid i ->
          throw new ApiProblem(ProblemType.INVALID_REQUEST, String.join("; ", i.problems()) + ".");
    }
  }

  private record Draft(String label, String query, AudiencePath path, String model) {}

  /** The draft DraftHomeExamples wrote; anything else is refused, never a 500. */
  private Draft read(@Nullable String details) {
    try {
      JsonNode d = json.readTree(details == null ? "" : details);
      return new Draft(
          text(d, "label"),
          text(d, "query"),
          AudiencePath.valueOf(text(d, "path")),
          text(d, "model"));
    } catch (JacksonException | IllegalArgumentException e) {
      throw new ApiProblem(ProblemType.INVALID_REQUEST, "The draft cannot be read.");
    }
  }

  private static String text(JsonNode d, String field) {
    JsonNode v = d.get(field);
    if (v == null || !v.isString() || v.asString().isBlank()) {
      throw new IllegalArgumentException(field);
    }
    return v.asString();
  }
}
