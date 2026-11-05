package ai.helper2.admin.usecase;

import ai.helper2.catalog.ReviewQueue;
import ai.helper2.catalog.usecase.ResolveReviewItem;
import ai.helper2.examples.ExampleOrigin;
import ai.helper2.examples.usecase.AddHomeExample;
import ai.helper2.platform.ApiProblem;
import ai.helper2.platform.ProblemType;
import ai.helper2.shared.AudiencePath;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * An administrator decides a review item. Accepting a drafted example (EXAMPLE_CANDIDATE, ADR 0049
 * §6) also adds it, AI_DRAFTED with its model, in the same transaction: when the example is refused
 * (a duplicate, a rule), the decision is not kept either, and the item stays open.
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

  @Transactional
  public ResolveReviewItem.Outcome resolve(
      UUID id, ReviewQueue.Status decision, String adminId, @Nullable String note) {
    ResolveReviewItem.Outcome outcome = resolve.resolve(id, decision, adminId, note);
    if (decision == ReviewQueue.Status.ACCEPTED
        && outcome instanceof ResolveReviewItem.Outcome.Resolved r
        && r.item().kind() == ReviewQueue.Kind.EXAMPLE_CANDIDATE) {
      addDrafted(r.item(), adminId);
    }
    return outcome;
  }

  private void addDrafted(ReviewQueue.Item item, String adminId) {
    JsonNode draft = json.readTree(item.details());
    switch (addExample.add(
        adminId,
        draft.get("label").asString(),
        draft.get("query").asString(),
        AudiencePath.valueOf(draft.get("path").asString()),
        List.of(item.resourceId()),
        ExampleOrigin.AI_DRAFTED,
        draft.get("model").asString())) {
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
}
