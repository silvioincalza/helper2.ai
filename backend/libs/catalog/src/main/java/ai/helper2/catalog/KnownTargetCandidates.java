package ai.helper2.catalog;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface KnownTargetCandidates {

  record Candidate(String name, KnownTarget.Kind kind, List<String> otherNames) {}

  record Proposed(int opened, int heldBack) {}

  Proposed proposeOwners(Collection<String> sourceIds, int minResources, int perDay);

  Optional<Candidate> of(UUID reviewItemId);

  boolean change(UUID reviewItemId, KnownTarget.Kind kind, List<String> names);
}
