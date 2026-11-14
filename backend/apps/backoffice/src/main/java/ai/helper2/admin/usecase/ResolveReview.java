package ai.helper2.admin.usecase;

import ai.helper2.catalog.KnownTargetCandidates;
import ai.helper2.catalog.ReviewQueue;
import ai.helper2.catalog.usecase.ManageKnownTargets;
import ai.helper2.catalog.usecase.ResolveReviewItem;
import ai.helper2.examples.usecase.AcceptExampleDraft;
import ai.helper2.platform.ApiProblem;
import ai.helper2.platform.ProblemType;
import ai.helper2.scout.usecase.AcceptOutcome;
import ai.helper2.scout.usecase.IndexAcceptedCandidate;
import ai.helper2.scout.usecase.InvalidCandidate;
import ai.helper2.scout.usecase.ListedResource;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public class ResolveReview {

  private final ReviewQueue queue;
  private final ResolveReviewItem resolve;
  private final AcceptExampleDraft acceptDraft;
  private final IndexAcceptedCandidate indexCandidate;
  private final KnownTargetCandidates targetCandidates;
  private final ManageKnownTargets knownTargets;

  public ResolveReview(
      ReviewQueue queue,
      ResolveReviewItem resolve,
      AcceptExampleDraft acceptDraft,
      IndexAcceptedCandidate indexCandidate,
      KnownTargetCandidates targetCandidates,
      ManageKnownTargets knownTargets) {
    this.targetCandidates = targetCandidates;
    this.knownTargets = knownTargets;
    this.queue = queue;
    this.resolve = resolve;
    this.acceptDraft = acceptDraft;
    this.indexCandidate = indexCandidate;
  }

  public record Decision(
      ResolveReviewItem.Outcome outcome, @Nullable ListedResource alreadyListed) {}

  public ResolveReviewItem.Outcome resolve(
      UUID id, ReviewQueue.Status decision, String adminId, @Nullable String note) {
    return resolve(id, decision, adminId, note, null);
  }

  public ResolveReviewItem.Outcome resolve(
      UUID id,
      ReviewQueue.Status decision,
      String adminId,
      @Nullable String note,
      @Nullable UUID batch) {
    return decide(id, decision, adminId, note, batch).outcome();
  }

  public Decision decide(
      UUID id, ReviewQueue.Status decision, String adminId, @Nullable String note) {
    return decide(id, decision, adminId, note, null);
  }

  private Decision decide(
      UUID id,
      ReviewQueue.Status decision,
      String adminId,
      @Nullable String note,
      @Nullable UUID batch) {
    if (decision == ReviewQueue.Status.ACCEPTED) {
      queue.lock(id);
    }
    Optional<ReviewQueue.Item> accepted =
        queue
            .find(id)
            .filter(i -> i.status() == ReviewQueue.Status.OPEN)
            .filter(i -> decision == ReviewQueue.Status.ACCEPTED);
    Optional<ReviewQueue.Item> addition = accepted.filter(i -> addsOnAccept(i.kind()));
    ListedResource listed = addition.map(item -> addAccepted(item, adminId)).orElse(null);
    ResolveReviewItem.Outcome outcome =
        resolve.resolve(id, decision, adminId, noteWith(note, listed), batch);
    if (addition.isPresent() && !(outcome instanceof ResolveReviewItem.Outcome.Resolved)) {
      throw new IllegalStateException("review item " + id + " was decided concurrently");
    }
    return new Decision(outcome, listed);
  }

  private static @Nullable String noteWith(@Nullable String note, @Nullable ListedResource listed) {
    if (listed == null) {
      return note;
    }
    String already = "Already listed as " + listed.getTitle() + ".";
    return note == null || note.isBlank() ? already : note + " " + already;
  }

  private static boolean addsOnAccept(ReviewQueue.Kind kind) {
    return kind == ReviewQueue.Kind.EXAMPLE_CANDIDATE
        || kind == ReviewQueue.Kind.RESOURCE_CANDIDATE
        || kind == ReviewQueue.Kind.KNOWN_TARGET_CANDIDATE;
  }

  private @Nullable ListedResource addAccepted(ReviewQueue.Item item, String adminId) {
    if (item.kind() == ReviewQueue.Kind.RESOURCE_CANDIDATE) {
      return indexCandidate(item);
    }
    if (item.kind() == ReviewQueue.Kind.KNOWN_TARGET_CANDIDATE) {
      addKnownTarget(item, adminId);
      return null;
    }
    addDrafted(item, adminId);
    return null;
  }

  private @Nullable ListedResource indexCandidate(ReviewQueue.Item item) {
    AcceptOutcome outcome;
    try {
      outcome = indexCandidate.accept(item.details());
    } catch (InvalidCandidate e) {
      throw new ApiProblem(ProblemType.INVALID_REQUEST, "The candidate cannot be read.");
    }
    return outcome instanceof AcceptOutcome.AlreadyListed listed ? listed.getResource() : null;
  }

  private void addKnownTarget(ReviewQueue.Item item, String adminId) {
    KnownTargetCandidates.Candidate candidate =
        targetCandidates
            .of(item.id())
            .orElseThrow(
                () -> new ApiProblem(ProblemType.INVALID_REQUEST, "The proposal cannot be read."));
    switch (knownTargets.addDiscovered(
        adminId, candidate.kind(), candidate.name(), candidate.otherNames())) {
      case ManageKnownTargets.Saved.Done d -> {}
      case ManageKnownTargets.Saved.Taken t ->
          throw new ApiProblem(
              ProblemType.DUPLICATE,
              t.name() + " already means " + t.holder() + ". Reject the proposal.");
      case ManageKnownTargets.Saved.Full f ->
          throw new ApiProblem(
              ProblemType.DUPLICATE,
              "The list holds at most " + ManageKnownTargets.MAX_TARGETS + " known targets.");
      case ManageKnownTargets.Saved.Invalid i ->
          throw new ApiProblem(
              ProblemType.INVALID_REQUEST, "The proposed name cannot be a known target.");
      case ManageKnownTargets.Saved.NotFound n ->
          throw new IllegalStateException("adding a known target cannot miss one");
    }
  }

  private void addDrafted(ReviewQueue.Item item, String adminId) {
    switch (acceptDraft.accept(adminId, item)) {
      case AcceptExampleDraft.Outcome.Added a -> {}
      case AcceptExampleDraft.Outcome.Unreadable u ->
          throw new ApiProblem(ProblemType.INVALID_REQUEST, "The draft cannot be read.");
      case AcceptExampleDraft.Outcome.Duplicate d ->
          throw new ApiProblem(
              ProblemType.DUPLICATE,
              "Another example already has this label or this query. Reject the draft, or change"
                  + " the example first.");
      case AcceptExampleDraft.Outcome.Invalid i ->
          throw new ApiProblem(ProblemType.INVALID_REQUEST, String.join("; ", i.problems()) + ".");
    }
  }
}
