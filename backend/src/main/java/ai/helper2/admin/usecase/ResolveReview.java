package ai.helper2.admin.usecase;

import ai.helper2.catalog.ReviewQueue;
import ai.helper2.catalog.usecase.ResolveReviewItem;
import ai.helper2.examples.ExampleOrigin;
import ai.helper2.examples.usecase.AddHomeExample;
import ai.helper2.platform.ApiProblem;
import ai.helper2.platform.ProblemType;
import ai.helper2.scout.usecase.IndexAcceptedCandidate;
import ai.helper2.scout.usecase.InvalidCandidate;
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
 * stays open, and the refused add stays in the audit trail (no rollback for it). Accepting a
 * resource candidate (RESOURCE_CANDIDATE, ADR 0051) first indexes it the same way; an unreadable
 * candidate is refused and the item stays open.
 */
@Service
public class ResolveReview {

  private final ReviewQueue queue;
  private final ResolveReviewItem resolve;
  private final AddHomeExample addExample;
  private final IndexAcceptedCandidate indexCandidate;
  private final ObjectMapper json;

  public ResolveReview(
      ReviewQueue queue,
      ResolveReviewItem resolve,
      AddHomeExample addExample,
      IndexAcceptedCandidate indexCandidate,
      ObjectMapper json) {
    this.queue = queue;
    this.resolve = resolve;
    this.addExample = addExample;
    this.indexCandidate = indexCandidate;
    this.json = json;
  }

  @Transactional(noRollbackFor = ApiProblem.class)
  public ResolveReviewItem.Outcome resolve(
      UUID id, ReviewQueue.Status decision, String adminId, @Nullable String note) {
    return resolve(id, decision, adminId, note, null);
  }

  /**
   * As above, for one item of a bulk decision [batch] (ResolveReviewItems): its own transaction.
   */
  @Transactional(noRollbackFor = ApiProblem.class)
  public ResolveReviewItem.Outcome resolve(
      UUID id,
      ReviewQueue.Status decision,
      String adminId,
      @Nullable String note,
      @Nullable UUID batch) {
    Optional<ReviewQueue.Item> accepted =
        queue
            .find(id)
            .filter(i -> i.status() == ReviewQueue.Status.OPEN)
            .filter(i -> decision == ReviewQueue.Status.ACCEPTED);
    Optional<ReviewQueue.Item> addition = accepted.filter(i -> addsOnAccept(i.kind()));
    addition.ifPresent(item -> addAccepted(item, adminId));
    ResolveReviewItem.Outcome outcome = resolve.resolve(id, decision, adminId, note, batch);
    if (addition.isPresent() && !(outcome instanceof ResolveReviewItem.Outcome.Resolved)) {
      // Decided by someone else meanwhile: undo the addition with the decision.
      throw new IllegalStateException("review item " + id + " was decided concurrently");
    }
    return outcome;
  }

  private static boolean addsOnAccept(ReviewQueue.Kind kind) {
    return kind == ReviewQueue.Kind.EXAMPLE_CANDIDATE
        || kind == ReviewQueue.Kind.RESOURCE_CANDIDATE;
  }

  private void addAccepted(ReviewQueue.Item item, String adminId) {
    if (item.kind() == ReviewQueue.Kind.RESOURCE_CANDIDATE) {
      indexCandidate(item);
    } else {
      addDrafted(item, adminId);
    }
  }

  /**
   * Indexes the candidate (IndexAcceptedCandidate checks it, and leaves a page another source has
   * indexed since as it is). A candidate that cannot be read or checked is refused; anything else
   * is a server fault.
   */
  private void indexCandidate(ReviewQueue.Item item) {
    try {
      indexCandidate.accept(item.details());
    } catch (InvalidCandidate e) {
      throw new ApiProblem(ProblemType.INVALID_REQUEST, "The candidate cannot be read.");
    }
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
