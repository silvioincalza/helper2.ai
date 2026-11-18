package ai.helper2.admin.usecase;

import ai.helper2.catalog.KnownTarget;
import ai.helper2.catalog.RelationshipTargets;
import ai.helper2.catalog.usecase.ManageKnownTargets;
import ai.helper2.platform.ApiProblem;
import ai.helper2.platform.BadRequestException;
import ai.helper2.platform.NotFoundException;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

public class DecideRelationshipTargetGroup {

  public static final int MAX_ITEMS = 1000;

  public sealed interface Choice {
    record Link(UUID resourceId) implements Choice {}

    record Known(KnownTarget.Kind kind) implements Choice {}

    record Reject() implements Choice {}
  }

  public record Outcome(UUID batch, int decided, int skipped, int left) {}

  private final RelationshipTargets targets;
  private final DecideRelationshipItem item;
  private final ManageKnownTargets knownTargets;

  public DecideRelationshipTargetGroup(
      RelationshipTargets targets, DecideRelationshipItem item, ManageKnownTargets knownTargets) {
    this.targets = targets;
    this.item = item;
    this.knownTargets = knownTargets;
  }

  public Outcome decide(String key, Choice choice, String by, @Nullable String note) {
    List<UUID> batchItems = targets.openItemsNamed(key, MAX_ITEMS);
    if (batchItems.isEmpty()) {
      throw new NotFoundException("No open relationship candidate has this target.");
    }
    if (choice instanceof Choice.Link l && !targets.resourceExists(l.resourceId())) {
      throw new BadRequestException("No resource has this id.");
    }
    UUID batch = UUID.randomUUID();
    int decided =
        switch (choice) {
          case Choice.Link l ->
              each(batchItems, id -> item.link(id, l.resourceId(), by, note, batch));
          case Choice.Reject r -> each(batchItems, id -> item.reject(id, by, note, batch));
          case Choice.Known k -> {
            addKnownTarget(key, k.kind(), by);
            yield each(batchItems, id -> item.confirmKnown(id, by, batch));
          }
        };
    int left = Math.toIntExact(targets.openItemCountNamed(key));
    return new Outcome(batch, decided, batchItems.size() - decided, left);
  }

  private void addKnownTarget(String key, KnownTarget.Kind kind, String by) {
    String name =
        targets
            .openGroupName(key)
            .orElseThrow(
                () -> new NotFoundException("No open relationship candidate has this target."));
    KnownTargetAnswer.requireAdded(
        knownTargets.add(by, kind, name, null),
        alreadyKnown -> {},
        "This name cannot be a known target.");
  }

  private static int each(List<UUID> ids, Predicate<UUID> decide) {
    int decided = 0;
    for (UUID id : ids) {
      if (decidedOne(id, decide)) {
        decided++;
      }
    }
    return decided;
  }

  private static boolean decidedOne(UUID id, Predicate<UUID> decide) {
    try {
      return decide.test(id);
    } catch (ApiProblem refused) {
      return false;
    }
  }
}
