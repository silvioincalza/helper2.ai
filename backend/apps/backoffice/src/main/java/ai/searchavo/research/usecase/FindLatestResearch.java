package ai.searchavo.research.usecase;

import ai.searchavo.research.ResearchRun;
import ai.searchavo.research.ResearchRuns;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public final class FindLatestResearch {

  private final ResearchRuns runs;

  public FindLatestResearch(ResearchRuns runs) {
    this.runs = runs;
  }

  public List<ResearchRun> of(Collection<UUID> itemIds) {
    return runs.latest(itemIds);
  }
}
