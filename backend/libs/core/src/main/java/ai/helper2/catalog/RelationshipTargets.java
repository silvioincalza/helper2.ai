package ai.helper2.catalog;

import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public interface RelationshipTargets {
  sealed interface Outcome {
    record Changed(@Nullable UUID targetId, String title) implements Outcome {}

    record NotFound() implements Outcome {}

    record NotOpen() implements Outcome {}

    record NotARelationship() implements Outcome {}

    record UnknownTarget() implements Outcome {}

    record SelfLink() implements Outcome {}

    record TitleTaken() implements Outcome {}
  }

  /**
   * The open relationship candidates of one target name: the name as most of them write it, how
   * many there are, their relationship kinds and up to three resources that name it.
   */
  record Group(String key, String name, int items, List<String> kinds, List<String> examples) {}

  Outcome change(UUID itemId, @Nullable UUID targetId, @Nullable String title);

  /** The open groups, the largest first. */
  List<Group> openGroups(int offset, int limit);

  long openGroupCount();

  boolean resourceExists(UUID resourceId);

  /** The name most open candidates of this key write, if any is open. */
  java.util.Optional<String> openGroupName(String key);

  /** The open relationship candidates whose target has this key, oldest first. */
  List<UUID> openItemsNamed(String key, int limit);

  int linkOpenCandidates(String by, String note);

  int confirmOpenKnownTargetCandidates(String by, String vendorNote, String serviceNote);
}
