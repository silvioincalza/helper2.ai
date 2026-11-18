package ai.helper2.admin.usecase;

import ai.helper2.admin.BulkDecisionRule;
import ai.helper2.catalog.ReviewEvidence;
import ai.helper2.catalog.ReviewQueue;
import ai.helper2.catalog.usecase.ResolveReviewItem;
import ai.helper2.platform.AuditTrail;
import ai.helper2.research.ResearchItems;
import ai.helper2.research.ResearchRun;
import ai.helper2.research.ResearchRuns;
import ai.helper2.research.ResearchSubject;
import ai.helper2.scout.usecase.CandidateEdit;
import ai.helper2.scout.usecase.CandidateItem;
import ai.helper2.scout.usecase.CandidateItems;
import ai.helper2.scout.usecase.CandidateRevision;
import ai.helper2.scout.usecase.EditCandidate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public class ResolveReviewItems {

  public enum Result {
    RESOLVED,
    NOT_OPEN,
    NOT_FOUND,
    NOT_ALLOWED
  }

  public record ItemResult(UUID id, Result result) {}

  public sealed interface Outcome {
    record Decided(UUID batch, List<ItemResult> items) implements Outcome {}

    record RepeatedId() implements Outcome {}

    record NotAdded(ResolveReview.Outcome.NotAdded why) implements Outcome {}
  }

  private sealed interface One {
    record Done(Result result) implements One {}

    record Stopped(ResolveReview.Outcome.NotAdded why) implements One {}
  }

  private final ReviewQueue queue;
  private final ReviewEvidence evidence;
  private final ResolveReview resolve;
  private final AuditTrail audit;
  private final ResearchRuns research;
  private final ResearchItems researchItems;
  private final CandidateItems candidates;
  private final EditCandidate editCandidate;

  public ResolveReviewItems(
      ReviewQueue queue,
      ReviewEvidence evidence,
      ResolveReview resolve,
      AuditTrail audit,
      ResearchRuns research,
      ResearchItems researchItems,
      CandidateItems candidates,
      EditCandidate editCandidate) {
    this.queue = queue;
    this.evidence = evidence;
    this.resolve = resolve;
    this.audit = audit;
    this.research = research;
    this.researchItems = researchItems;
    this.candidates = candidates;
    this.editCandidate = editCandidate;
  }

  public Outcome resolve(
      List<UUID> ids, ReviewItemView.Status status, String adminId, @Nullable String note) {
    if (ids.stream().distinct().count() != ids.size()) {
      return new Outcome.RepeatedId();
    }
    ReviewQueue.Status decision = status.inQueue();
    UUID batch = UUID.randomUUID();
    List<ItemResult> results = new ArrayList<>();
    for (UUID id : ids) {
      switch (decideOne(id, decision, adminId, note, batch)) {
        case One.Done done -> results.add(new ItemResult(id, done.result()));
        case One.Stopped stopped -> {
          return new Outcome.NotAdded(stopped.why());
        }
      }
    }
    return new Outcome.Decided(batch, List.copyOf(results));
  }

  private One decideOne(
      UUID id, ReviewQueue.Status decision, String adminId, @Nullable String note, UUID batch) {
    Optional<ReviewQueue.Item> item = queue.find(id);
    if (item.isPresent() && item.get().status() == ReviewQueue.Status.OPEN) {
      Optional<ResearchRun> run = latestResearch(item.get(), decision);
      if (!allowed(item.get(), decision, run)
          || !researchedValuesApplied(item.get(), run, adminId)) {
        return new One.Done(notAllowed(id, adminId, batch));
      }
    }
    return switch (resolve.resolve(id, decision, adminId, note, batch)) {
      case ResolveReview.Outcome.Resolved r -> new One.Done(Result.RESOLVED);
      case ResolveReview.Outcome.NotOpen n -> new One.Done(Result.NOT_OPEN);
      case ResolveReview.Outcome.NotFound f -> new One.Done(Result.NOT_FOUND);
      case ResolveReview.Outcome.NotAdded why -> new One.Stopped(why);
    };
  }

  private Result notAllowed(UUID id, String adminId, UUID batch) {
    audit.record(
        adminId,
        AuditTrail.Action.RESOLVE_REVIEW_ITEM,
        id.toString(),
        ResolveReviewItem.inBatch(Result.NOT_ALLOWED.name(), batch));
    return Result.NOT_ALLOWED;
  }

  private Optional<ResearchRun> latestResearch(ReviewQueue.Item item, ReviewQueue.Status decision) {
    if (decision != ReviewQueue.Status.ACCEPTED || !researched(item)) {
      return Optional.empty();
    }
    return research.latest(List.of(item.id())).stream().findFirst();
  }

  private static boolean researched(ReviewQueue.Item item) {
    return item.kind() == ReviewQueue.Kind.RESOURCE_CANDIDATE
        || item.kind() == ReviewQueue.Kind.COURSE_CANDIDATE;
  }

  private boolean allowed(
      ReviewQueue.Item item, ReviewQueue.Status decision, Optional<ResearchRun> run) {
    List<String> conflicting =
        decision == ReviewQueue.Status.ACCEPTED
                && item.kind() == ReviewQueue.Kind.ENRICHMENT_VALIDATION
            ? evidence.conflictingFields(item.id())
            : List.of();
    BulkDecisionRule.Research verdict =
        ResearchedAcceptance.acceptable(run.orElse(null))
            ? BulkDecisionRule.Research.KEEP_WITHOUT_CONFLICT
            : BulkDecisionRule.Research.NOT_KEEP;
    return BulkDecisionRule.allows(decision, item.kind(), conflicting, verdict);
  }

  private boolean researchedValuesApplied(
      ReviewQueue.Item item, Optional<ResearchRun> run, String adminId) {
    if (run.isEmpty() || run.get().result() == null) {
      return true;
    }
    Optional<ResearchSubject> now = researchItems.subject(item.id());
    if (now.isEmpty() || !ResearchedAcceptance.describes(run.get().result(), now.get())) {
      return false;
    }
    Optional<CandidateEdit> edit;
    try {
      edit = ResearchedAcceptance.edit(run.get().result());
    } catch (IllegalArgumentException e) {
      return false;
    }
    if (edit.isEmpty()) {
      return true;
    }
    CandidateItem candidate = candidates.find(item.id());
    return candidate != null
        && editCandidate.edit(
                item.id(), CandidateRevision.of(candidate.getDetails()), edit.get(), adminId)
            instanceof EditCandidate.Outcome.Edited;
  }
}
