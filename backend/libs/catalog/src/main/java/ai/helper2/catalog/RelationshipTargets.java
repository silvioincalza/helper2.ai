package ai.helper2.catalog;

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

  Outcome change(UUID itemId, @Nullable UUID targetId, @Nullable String title);

  int linkOpenCandidates(String by, String note);

  int confirmOpenKnownTargetCandidates(String by, String vendorNote, String serviceNote);
}
