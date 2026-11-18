package ai.helper2.admin.usecase;

import ai.helper2.admin.BulkDecisionRule;
import ai.helper2.catalog.ReviewEvidence;
import ai.helper2.catalog.ReviewQueue;
import ai.helper2.catalog.usecase.ResolveReviewItem;
import ai.helper2.platform.AuditTrail;
import ai.helper2.platform.BadRequestException;
import ai.helper2.research.ResearchItems;
import ai.helper2.research.ResearchRun;
import ai.helper2.research.ResearchRuns;
import ai.helper2.research.ResearchSubject;
import ai.helper2.scout.usecase.CandidateEdit;
import ai.helper2.scout.usecase.CandidateItem;
import ai.helper2.scout.usecase.CandidateItems;
import ai.helper2.scout.usecase.EditCandidate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public class ResolveReviewItems {

  public static final int MAX_ITEMS = 100;

  public enum Result {
    RESOLVED,
    NOT_OPEN,
    NOT_FOUND,
    NOT_ALLOWED
  }

  public record ItemResult(UUID id, Result result) {}

  public record Outcome(UUID batch, List<ItemResult> items) {}

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
      List<UUID> ids, ReviewQueue.Status decision, String adminId, @Nullable String note) {
    requireValid(ids, decision);
    UUID batch = UUID.randomUUID();
    List<ItemResult> results =
        ids.stream()
            .map(id -> new ItemResult(id, decideOne(id, decision, adminId, note, batch)))
            .toList();
    return new Outcome(batch, results);
  }

  private static void requireValid(List<UUID> ids, ReviewQueue.Status decision) {
    if (ids.isEmpty() || ids.size() > MAX_ITEMS) {
      throw new BadRequestException("ids must hold 1 to " + MAX_ITEMS + " items");
    }
    if (ids.stream().distinct().count() != ids.size()) {
      throw new BadRequestException("ids must be unique");
    }
    if (decision != ReviewQueue.Status.ACCEPTED && decision != ReviewQueue.Status.REJECTED) {
      throw new BadRequestException("decision must be ACCEPTED or REJECTED");
    }
  }

  private Result decideOne(
      UUID id, ReviewQueue.Status decision, String adminId, @Nullable String note, UUID batch) {
    Optional<ReviewQueue.Item> item = queue.find(id);
    if (item.isPresent() && item.get().status() == ReviewQueue.Status.OPEN) {
      Optional<ResearchRun> run = latestResearch(item.get(), decision);
      if (!allowed(item.get(), decision, run)
          || !researchedValuesApplied(item.get(), run, adminId)) {
        return notAllowed(id, adminId, batch);
      }
    }
    return switch (resolve.resolve(id, decision, adminId, note, batch)) {
      case ResolveReviewItem.Outcome.Resolved r -> Result.RESOLVED;
      case ResolveReviewItem.Outcome.NotOpen n -> Result.NOT_OPEN;
      case ResolveReviewItem.Outcome.NotFound f -> Result.NOT_FOUND;
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
        && editCandidate.edit(item.id(), candidate.getDetails(), edit.get(), adminId)
            instanceof EditCandidate.Outcome.Edited;
  }
}
