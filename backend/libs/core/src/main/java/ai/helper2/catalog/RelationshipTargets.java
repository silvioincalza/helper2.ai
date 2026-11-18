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

  record Group(String key, String name, int items, List<String> kinds, List<String> examples) {}

  Outcome change(UUID itemId, @Nullable UUID targetId, @Nullable String title);

  List<Group> openGroups(int offset, int limit);

  long openGroupCount();

  boolean resourceExists(UUID resourceId);

  java.util.Optional<String> openGroupName(String key);

  List<UUID> openItemsNamed(String key, int limit);

  int linkOpenCandidates(String by, String note);

  int confirmOpenKnownTargetCandidates(
      String by, String vendorNote, String serviceNote, String toolNote);

  boolean confirmKnownTargetCandidate(
      UUID itemId, String by, String vendorNote, String serviceNote, String toolNote);

  long openItemCountNamed(String key);
}
