package ai.searchavo.catalog.usecase;

import ai.searchavo.catalog.RelationshipTargets;
import ai.searchavo.platform.AuditTrail;
import ai.searchavo.shared.Refused;
import ai.searchavo.shared.ResourceType;
import ai.searchavo.shared.UnitOfWork;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public class ChangeRelationshipTarget {

  static final int MAX_NAME = 120;

  public sealed interface Target {
    record Existing(UUID resourceId) implements Target {}

    record Added(String title, String url, ResourceType type) implements Target {}

    record Renamed(String title) implements Target {}
  }

  public sealed interface Outcome {
    record Changed() implements Outcome {}

    record InvalidName(int maxLength) implements Outcome {}

    record InvalidResourceTitle(int maxLength) implements Outcome {}

    record InvalidResourceUrl() implements Outcome {}

    record InvalidResourceType(ResourceType type) implements Outcome {}

    record NotFound() implements Outcome, Refused {}

    record NotOpen() implements Outcome, Refused {}

    record NotARelationship() implements Outcome, Refused {}

    record TitleTaken() implements Outcome, Refused {}

    record UnknownTarget() implements Outcome, Refused {}

    record SelfLink() implements Outcome, Refused {}
  }

  private final RelationshipTargets targets;
  private final AddCuratedResource add;
  private final AuditTrail audit;
  private final UnitOfWork unitOfWork;

  public ChangeRelationshipTarget(
      RelationshipTargets targets,
      AddCuratedResource add,
      AuditTrail audit,
      UnitOfWork unitOfWork) {
    this.targets = targets;
    this.add = add;
    this.audit = audit;
    this.unitOfWork = unitOfWork;
  }

  public Outcome change(UUID itemId, Target target, String by) {
    return unitOfWork.run(() ->
        switch (target) {
          case Target.Existing existing -> link(itemId, existing.resourceId(), by);
          case Target.Added added -> linkToAdded(itemId, added, by);
          case Target.Renamed renamed -> rename(itemId, renamed.title(), by);
        });
  }

  private Outcome link(UUID itemId, UUID resourceId, String by) {
    return recorded(itemId, targets.change(itemId, resourceId, null), by);
  }

  private Outcome linkToAdded(UUID itemId, Target.Added resource, String by) {
    return switch (add.add(resource.title(), resource.url(), resource.type())) {
      case AddCuratedResource.Outcome.Added added -> linkToNew(itemId, added.id(), by);
      case AddCuratedResource.Outcome.AlreadyListed listed -> link(itemId, listed.id(), by);
      case AddCuratedResource.Outcome.InvalidTitle title ->
          new Outcome.InvalidResourceTitle(title.maxLength());
      case AddCuratedResource.Outcome.InvalidUrl url -> new Outcome.InvalidResourceUrl();
      case AddCuratedResource.Outcome.NotACuratedType type ->
          new Outcome.InvalidResourceType(type.type());
    };
  }

  private Outcome linkToNew(UUID itemId, UUID resourceId, String by) {
    RelationshipTargets.Outcome linked = targets.change(itemId, resourceId, null);
    if (linked instanceof RelationshipTargets.Outcome.Changed) {
      audit.record(by, AuditTrail.Action.ADD_RESOURCE, resourceId.toString(), "ADDED");
    }
    return recorded(itemId, linked, by);
  }

  private Outcome rename(UUID itemId, @Nullable String title, String by) {
    String name = title == null ? "" : title.strip();
    if (!isAName(name)) {
      return new Outcome.InvalidName(MAX_NAME);
    }
    return recorded(itemId, targets.change(itemId, null, name), by);
  }

  private static boolean isAName(String name) {
    return !name.isEmpty()
        && name.length() <= MAX_NAME
        && name.chars().noneMatch(Character::isISOControl);
  }

  private Outcome recorded(UUID itemId, RelationshipTargets.Outcome outcome, String by) {
    return switch (outcome) {
      case RelationshipTargets.Outcome.Changed changed -> {
        audit.record(
            by,
            AuditTrail.Action.CHANGE_RELATIONSHIP_TARGET,
            itemId.toString(),
            changed.targetId() == null ? "RENAMED" : "LINKED " + changed.targetId());
        yield new Outcome.Changed();
      }
      case RelationshipTargets.Outcome.NotFound notFound -> new Outcome.NotFound();
      case RelationshipTargets.Outcome.NotOpen notOpen -> new Outcome.NotOpen();
      case RelationshipTargets.Outcome.NotARelationship other -> new Outcome.NotARelationship();
      case RelationshipTargets.Outcome.TitleTaken taken -> new Outcome.TitleTaken();
      case RelationshipTargets.Outcome.UnknownTarget unknown -> new Outcome.UnknownTarget();
      case RelationshipTargets.Outcome.SelfLink self -> new Outcome.SelfLink();
    };
  }
}
