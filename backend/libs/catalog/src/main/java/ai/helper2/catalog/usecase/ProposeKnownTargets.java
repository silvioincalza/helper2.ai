package ai.helper2.catalog.usecase;

import ai.helper2.catalog.KnownTargetCandidates;
import ai.helper2.catalog.KnownTargetCandidates.Proposed;
import java.util.Collection;
import java.util.List;

public class ProposeKnownTargets {

  private final KnownTargetCandidates candidates;
  private final List<String> ownerSources;
  private final int ownerMinResources;
  private final int perDay;

  public ProposeKnownTargets(
      KnownTargetCandidates candidates,
      Collection<String> ownerSources,
      int ownerMinResources,
      int perDay) {
    this.candidates = candidates;
    this.ownerSources = List.copyOf(ownerSources);
    this.ownerMinResources = ownerMinResources;
    this.perDay = perDay;
  }

  public Proposed propose() {
    if (perDay <= 0 || ownerSources.isEmpty()) {
      return new Proposed(0, 0);
    }
    return candidates.proposeOwners(ownerSources, ownerMinResources, perDay);
  }
}
