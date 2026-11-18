package ai.helper2.catalog;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public interface KnownTargetCandidates {

  record Candidate(String name, KnownTarget.Kind kind, List<String> otherNames) {}

  record Proposed(int opened, int heldBack) {}

  Proposed proposeOwners(Collection<String> sourceIds, int minResources, int perDay);

  Optional<Candidate> of(UUID reviewItemId);

  boolean change(UUID reviewItemId, KnownTarget.Kind kind, List<String> names);

  record Proposal(
      String name,
      KnownTarget.Kind kind,
      String rule,
      int count,
      List<String> examples,
      List<String> otherNames,
      boolean edited) {}

  String OWNER_RULE = "OWNER";

  Optional<Proposal> shown(@Nullable String details);
}
