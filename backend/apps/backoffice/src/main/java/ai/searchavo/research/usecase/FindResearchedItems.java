package ai.searchavo.research.usecase;

import ai.searchavo.research.ResearchFilter;
import ai.searchavo.research.ResearchRuns;
import java.util.Set;
import java.util.UUID;

public final class FindResearchedItems {

  private final ResearchRuns runs;

  public FindResearchedItems(ResearchRuns runs) {
    this.runs = runs;
  }

  public Set<UUID> ids(ResearchFilter filter) {
    return runs.itemsWhere(filter);
  }
}
