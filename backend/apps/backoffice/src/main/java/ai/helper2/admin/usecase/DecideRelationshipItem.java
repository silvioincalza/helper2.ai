package ai.helper2.admin.usecase;

import ai.helper2.catalog.RelationshipTargets;
import ai.helper2.catalog.ReviewQueue;
import ai.helper2.catalog.usecase.LinkKnownRelationshipTargets;
import ai.helper2.catalog.usecase.ResolveReviewItem;
import ai.helper2.platform.AuditTrail;
import ai.helper2.shared.Refused;
import ai.helper2.shared.UnitOfWork;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public class DecideRelationshipItem {

  public sealed interface Outcome {
    record Decided() implements Outcome {}

    record Skipped() implements Outcome {}

    record LinkUndone() implements Outcome, Refused {}
  }

  private final RelationshipTargets targets;
  private final ResolveReview resolve;
  private final AuditTrail audit;
  private final UnitOfWork unitOfWork;

  public DecideRelationshipItem(
      RelationshipTargets targets, ResolveReview resolve, AuditTrail audit, UnitOfWork unitOfWork) {
    this.targets = targets;
    this.resolve = resolve;
    this.audit = audit;
    this.unitOfWork = unitOfWork;
  }

  public Outcome link(UUID id, UUID resourceId, String by, @Nullable String note, UUID batch) {
    return unitOfWork.run(() -> linked(id, resourceId, by, note, batch));
  }

  public Outcome reject(UUID id, String by, @Nullable String note, UUID batch) {
    return unitOfWork.run(() ->
        resolved(id, ReviewQueue.Status.REJECTED, by, note, batch)
            ? new Outcome.Decided()
            : new Outcome.Skipped());
  }

  public Outcome confirmKnown(UUID id, String by, UUID batch) {
    return unitOfWork.run(() -> confirmedKnown(id, by, batch));
  }

  private Outcome linked(UUID id, UUID resourceId, String by, @Nullable String note, UUID batch) {
    if (!(targets.change(id, resourceId, null) instanceof RelationshipTargets.Outcome.Changed)) {
      return new Outcome.Skipped();
    }
    audit.record(
        by, AuditTrail.Action.CHANGE_RELATIONSHIP_TARGET, id.toString(), "LINKED " + resourceId);
    return resolved(id, ReviewQueue.Status.ACCEPTED, by, note, batch)
        ? new Outcome.Decided()
        : new Outcome.LinkUndone();
  }

  private Outcome confirmedKnown(UUID id, String by, UUID batch) {
    if (!targets.confirmKnownTargetCandidate(
        id,
        by,
        LinkKnownRelationshipTargets.VENDOR_NOTE,
        LinkKnownRelationshipTargets.SERVICE_NOTE,
        LinkKnownRelationshipTargets.TOOL_NOTE)) {
      return new Outcome.Skipped();
    }
    audit.record(
        by,
        AuditTrail.Action.RESOLVE_REVIEW_ITEM,
        id.toString(),
        ResolveReviewItem.inBatch(ReviewQueue.Status.SUPERSEDED.name(), batch));
    return new Outcome.Decided();
  }

  private boolean resolved(
      UUID id, ReviewQueue.Status decision, String by, @Nullable String note, UUID batch) {
    return resolve.resolve(id, decision, by, note, batch) instanceof ResolveReview.Outcome.Resolved;
  }
}
