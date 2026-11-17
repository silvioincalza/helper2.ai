package ai.helper2.admin.usecase;

import ai.helper2.catalog.KnownTarget;
import ai.helper2.catalog.RelationshipTargets;
import ai.helper2.catalog.ReviewQueue;
import ai.helper2.catalog.usecase.ChangeRelationshipTarget;
import ai.helper2.catalog.usecase.LinkKnownRelationshipTargets;
import ai.helper2.catalog.usecase.ManageKnownTargets;
import ai.helper2.catalog.usecase.ResolveReviewItem;
import ai.helper2.platform.ApiProblem;
import ai.helper2.platform.BadRequestException;
import ai.helper2.platform.NotFoundException;
import ai.helper2.platform.ProblemType;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One decision for every open relationship candidate of one target name (S6.2.10).
 *
 * <p>A relationship candidate is open because its target is not a known resource: its evidence
 * already passed. The open question is the same for every item of the name, so one answer closes
 * them all. Each item is still decided on its own, with its own audit entry in one batch.
 */
public class DecideRelationshipTargetGroup {

  /** The most items one call decides; the rest stay open for the next call. */
  public static final int MAX_ITEMS = 1000;

  public sealed interface Choice {
    /** The target is this catalog resource. */
    record Link(UUID resourceId) implements Choice {}

    /** The target is a vendor or a service: it becomes a known target. */
    record Known(KnownTarget.Kind kind) implements Choice {}

    /** The name is not a real target. */
    record Reject() implements Choice {}
  }

  public record Outcome(UUID batch, int decided, int skipped, int left) {}

  private final RelationshipTargets targets;
  private final ChangeRelationshipTarget changeTarget;
  private final ResolveReview resolve;
  private final ManageKnownTargets knownTargets;

  public DecideRelationshipTargetGroup(
      RelationshipTargets targets,
      ChangeRelationshipTarget changeTarget,
      ResolveReview resolve,
      ManageKnownTargets knownTargets) {
    this.targets = targets;
    this.changeTarget = changeTarget;
    this.resolve = resolve;
    this.knownTargets = knownTargets;
  }

  public Outcome decide(String key, Choice choice, String by, @Nullable String note) {
    List<UUID> items = targets.openItemsNamed(key, MAX_ITEMS + 1);
    if (items.isEmpty()) {
      throw new NotFoundException("No open relationship candidate has this target.");
    }
    int left = Math.max(0, items.size() - MAX_ITEMS);
    List<UUID> now = items.subList(0, items.size() - left);
    if (choice instanceof Choice.Link l && !targets.resourceExists(l.resourceId())) {
      throw new BadRequestException("No resource has this id.");
    }
    UUID batch = UUID.randomUUID();
    return switch (choice) {
      case Choice.Link l -> each(now, batch, left, id -> link(id, l.resourceId(), by, note, batch));
      case Choice.Reject r ->
          each(now, batch, left, id -> decided(id, ReviewQueue.Status.REJECTED, by, note, batch));
      case Choice.Known k -> known(key, k.kind(), by, batch, now.size(), left);
    };
  }

  private boolean link(UUID id, UUID resourceId, String by, @Nullable String note, UUID batch) {
    try {
      changeTarget.change(id, new ChangeRelationshipTarget.Target.Existing(resourceId), by);
    } catch (ApiProblem e) {
      // Decided meanwhile, the resource itself, or a resource that already has this target:
      // this item stays as it is, the others go on.
      return false;
    }
    return decided(id, ReviewQueue.Status.ACCEPTED, by, note, batch);
  }

  private boolean decided(
      UUID id, ReviewQueue.Status decision, String by, @Nullable String note, UUID batch) {
    return resolve.resolve(id, decision, by, note, batch)
        instanceof ResolveReviewItem.Outcome.Resolved;
  }

  private Outcome known(
      String key, KnownTarget.Kind kind, String by, UUID batch, int asked, int left) {
    String name =
        targets
            .openGroupName(key)
            .orElseThrow(
                () -> new NotFoundException("No open relationship candidate has this target."));
    switch (knownTargets.add(by, kind, name, null)) {
      case ManageKnownTargets.Saved.Done d -> {}
      case ManageKnownTargets.Saved.Taken t -> {}
      case ManageKnownTargets.Saved.Full f ->
          throw new ApiProblem(
              ProblemType.DUPLICATE,
              "The list holds at most " + ManageKnownTargets.MAX_TARGETS + " known targets.");
      case ManageKnownTargets.Saved.Invalid i ->
          throw new ApiProblem(ProblemType.INVALID_REQUEST, "This name cannot be a known target.");
      case ManageKnownTargets.Saved.NotFound n ->
          throw new IllegalStateException("adding a known target cannot miss one");
    }
    int closed =
        targets.confirmOpenKnownTargetCandidates(
            by,
            LinkKnownRelationshipTargets.VENDOR_NOTE,
            LinkKnownRelationshipTargets.SERVICE_NOTE);
    return new Outcome(batch, closed, Math.max(0, asked - closed), left);
  }

  private static Outcome each(
      List<UUID> ids, UUID batch, int left, java.util.function.Predicate<UUID> decide) {
    int decided = 0;
    for (UUID id : ids) {
      if (decide.test(id)) {
        decided++;
      }
    }
    return new Outcome(batch, decided, ids.size() - decided, left);
  }
}
