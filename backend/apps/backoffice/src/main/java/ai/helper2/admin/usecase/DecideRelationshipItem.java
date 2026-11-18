package ai.helper2.admin.usecase;

import ai.helper2.catalog.RelationshipTargets;
import ai.helper2.catalog.ReviewQueue;
import ai.helper2.catalog.usecase.LinkKnownRelationshipTargets;
import ai.helper2.catalog.usecase.ResolveReviewItem;
import ai.helper2.platform.ApiProblem;
import ai.helper2.platform.AuditTrail;
import ai.helper2.platform.ProblemType;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public class DecideRelationshipItem {

  private final RelationshipTargets targets;
  private final ResolveReview resolve;
  private final AuditTrail audit;

  public DecideRelationshipItem(
      RelationshipTargets targets, ResolveReview resolve, AuditTrail audit) {
    this.targets = targets;
    this.resolve = resolve;
    this.audit = audit;
  }

  public boolean link(UUID id, UUID resourceId, String by, @Nullable String note, UUID batch) {
    if (!(targets.change(id, resourceId, null) instanceof RelationshipTargets.Outcome.Changed)) {
      return false;
    }
    audit.record(
        by, AuditTrail.Action.CHANGE_RELATIONSHIP_TARGET, id.toString(), "LINKED " + resourceId);
    if (!resolved(id, ReviewQueue.Status.ACCEPTED, by, note, batch)) {
      throw new ApiProblem(ProblemType.ALREADY_DECIDED, "The item is no longer open.");
    }
    return true;
  }

  public boolean reject(UUID id, String by, @Nullable String note, UUID batch) {
    return resolved(id, ReviewQueue.Status.REJECTED, by, note, batch);
  }

  public boolean confirmKnown(UUID id, String by, UUID batch) {
    if (!targets.confirmKnownTargetCandidate(
        id,
        by,
        LinkKnownRelationshipTargets.VENDOR_NOTE,
        LinkKnownRelationshipTargets.SERVICE_NOTE,
        LinkKnownRelationshipTargets.TOOL_NOTE)) {
      return false;
    }
    audit.record(
        by,
        AuditTrail.Action.RESOLVE_REVIEW_ITEM,
        id.toString(),
        ResolveReviewItem.inBatch(ReviewQueue.Status.SUPERSEDED.name(), batch));
    return true;
  }

  private boolean resolved(
      UUID id, ReviewQueue.Status decision, String by, @Nullable String note, UUID batch) {
    return resolve.resolve(id, decision, by, note, batch)
        instanceof ResolveReviewItem.Outcome.Resolved;
  }
}
