package ai.helper2.admin.usecase;

import ai.helper2.catalog.KnownTarget;
import ai.helper2.catalog.RelationshipTargets;
import ai.helper2.catalog.usecase.ManageKnownTargets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

public class DecideRelationshipTargetGroup {

  public static final int MAX_ITEMS = 1000;

  public sealed interface Choice {
    record Link(UUID resourceId) implements Choice {}

    record Known(KnownTarget.Kind kind) implements Choice {}

    record Reject() implements Choice {}
  }

  public sealed interface Outcome {
    record Decided(UUID batch, int decided, int skipped, int left) implements Outcome {}

    sealed interface NothingDecided extends Outcome {}

    record NoOpenItems() implements NothingDecided {}

    record UnknownResource() implements NothingDecided {}

    record InvalidKnownTargetName() implements NothingDecided {}

    record KnownTargetListFull(int limit) implements NothingDecided {}
  }

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
      return new Outcome.NoOpenItems();
    }
    UUID batch = UUID.randomUUID();
    return switch (choice) {
      case Choice.Link l ->
          targets.resourceExists(l.resourceId())
              ? decided(
                  key, batch, batchItems, id -> item.link(id, l.resourceId(), by, note, batch))
              : new Outcome.UnknownResource();
      case Choice.Reject r ->
          decided(key, batch, batchItems, id -> item.reject(id, by, note, batch));
      case Choice.Known k ->
          addKnownTarget(key, k.kind(), by)
              .orElseGet(
                  () -> decided(key, batch, batchItems, id -> item.confirmKnown(id, by, batch)));
    };
  }

  private Optional<Outcome> addKnownTarget(String key, KnownTarget.Kind kind, String by) {
    Optional<String> name = targets.openGroupName(key);
    if (name.isEmpty()) {
      return Optional.of(new Outcome.NoOpenItems());
    }
    return switch (knownTargets.add(by, kind, name.get(), null)) {
      case ManageKnownTargets.Saved.Done d -> Optional.empty();
      case ManageKnownTargets.Saved.Taken t -> Optional.empty();
      case ManageKnownTargets.Saved.Full f ->
          Optional.of(new Outcome.KnownTargetListFull(ManageKnownTargets.MAX_TARGETS));
      case ManageKnownTargets.Saved.Invalid i -> Optional.of(new Outcome.InvalidKnownTargetName());
      case ManageKnownTargets.Saved.NotFound n ->
          throw new IllegalStateException("adding a known target cannot miss one");
    };
  }

  private Outcome decided(
      String key,
      UUID batch,
      List<UUID> ids,
      Function<UUID, DecideRelationshipItem.Outcome> decide) {
    int decided = 0;
    for (UUID id : ids) {
      if (decide.apply(id) instanceof DecideRelationshipItem.Outcome.Decided) {
        decided++;
      }
    }
    int left = Math.toIntExact(targets.openItemCountNamed(key));
    return new Outcome.Decided(batch, decided, ids.size() - decided, left);
  }
}
