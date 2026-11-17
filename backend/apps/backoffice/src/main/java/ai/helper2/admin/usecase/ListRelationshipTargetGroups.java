package ai.helper2.admin.usecase;

import ai.helper2.catalog.RelationshipTargets;
import ai.helper2.catalog.RelationshipTargets.Group;
import java.util.List;

public class ListRelationshipTargetGroups {

  public record Page(List<Group> groups, long total) {}

  private final RelationshipTargets targets;

  public ListRelationshipTargetGroups(RelationshipTargets targets) {
    this.targets = targets;
  }

  public Page list(int page, int size) {
    return new Page(
        targets.openGroups(Math.multiplyExact(page, size), size), targets.openGroupCount());
  }
}
