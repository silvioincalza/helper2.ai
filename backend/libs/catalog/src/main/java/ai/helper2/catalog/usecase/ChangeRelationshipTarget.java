package ai.helper2.catalog.usecase;

import ai.helper2.catalog.internal.RelationshipTargets;
import ai.helper2.identity.AuditTrail;
import ai.helper2.platform.ApiProblem;
import ai.helper2.platform.BadRequestException;
import ai.helper2.platform.NotFoundException;
import ai.helper2.platform.ProblemType;
import ai.helper2.shared.ResourceType;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * An admin points an open relationship candidate at the right resource (ADR 0064): one already in
 * the catalog, one added now ({@link AddCuratedResource}), or no resource under another name. The
 * item stays open; accepting it makes the link VERIFIED. One transaction: a change that is refused
 * adds no resource. Each change is in the audit trail, and so is a resource added for it.
 */
@Service
public class ChangeRelationshipTarget {

  static final int MAX_NAME = 120;

  /** What the admin chose. */
  public sealed interface Target {
    record Existing(UUID resourceId) implements Target {}

    record Added(String title, String url, ResourceType type) implements Target {}

    record Renamed(String title) implements Target {}
  }

  private final RelationshipTargets targets;
  private final AddCuratedResource add;
  private final AuditTrail audit;

  public ChangeRelationshipTarget(
      RelationshipTargets targets, AddCuratedResource add, AuditTrail audit) {
    this.targets = targets;
    this.add = add;
    this.audit = audit;
  }

  @Transactional
  public void change(UUID itemId, Target target, String by) {
    RelationshipTargets.Outcome outcome =
        switch (target) {
          case Target.Existing e -> targets.change(itemId, e.resourceId(), null);
          case Target.Added a -> linkToAdded(itemId, a, by);
          case Target.Renamed r -> targets.change(itemId, null, checkedName(r.title()));
        };
    if (!(outcome instanceof RelationshipTargets.Outcome.Changed changed)) {
      throw refused(outcome);
    }
    audit.record(
        by,
        AuditTrail.Action.CHANGE_RELATIONSHIP_TARGET,
        itemId.toString(),
        changed.targetId() == null ? "RENAMED" : "LINKED " + changed.targetId());
  }

  /** Adds the resource (or finds the one at its address), then links to it. */
  private RelationshipTargets.Outcome linkToAdded(UUID itemId, Target.Added a, String by) {
    AddCuratedResource.Outcome resource = add.add(a.title(), a.url(), a.type());
    RelationshipTargets.Outcome outcome = targets.change(itemId, resource.id(), null);
    if (resource.added() && outcome instanceof RelationshipTargets.Outcome.Changed) {
      audit.record(by, AuditTrail.Action.ADD_RESOURCE, resource.id().toString(), "ADDED");
    }
    return outcome;
  }

  private static String checkedName(@Nullable String title) {
    String name = title == null ? "" : title.strip();
    if (name.isEmpty()
        || name.length() > MAX_NAME
        || name.chars().anyMatch(Character::isISOControl)) {
      throw new BadRequestException("The name must be 1 to " + MAX_NAME + " characters.");
    }
    return name;
  }

  /** The problem for an outcome that changed nothing; thrown, so nothing added stays. */
  private static RuntimeException refused(RelationshipTargets.Outcome outcome) {
    return switch (outcome) {
      case RelationshipTargets.Outcome.NotFound n ->
          new NotFoundException("Review item not found.");
      case RelationshipTargets.Outcome.NotOpen n ->
          new ApiProblem(ProblemType.ALREADY_DECIDED, "The item is no longer open.");
      case RelationshipTargets.Outcome.NotARelationship n ->
          new ApiProblem(ProblemType.NOT_APPLICABLE, "The item is not a relationship candidate.");
      case RelationshipTargets.Outcome.TitleTaken t ->
          new ApiProblem(
              ProblemType.DUPLICATE, "The resource already has a relationship with this target.");
      case RelationshipTargets.Outcome.UnknownTarget u ->
          new BadRequestException("No resource has this id.");
      case RelationshipTargets.Outcome.SelfLink s ->
          new BadRequestException("A resource cannot point at itself.");
      case RelationshipTargets.Outcome.Changed c -> new IllegalStateException("changed");
    };
  }
}
