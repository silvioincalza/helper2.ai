package ai.helper2.research.usecase;

import ai.helper2.research.ResearchRuns;
import java.time.Clock;
import java.util.UUID;

public final class CancelResearch {

  private final ResearchRuns runs;
  private final Clock clock;

  public CancelResearch(ResearchRuns runs, Clock clock) {
    this.runs = runs;
    this.clock = clock;
  }

  public boolean cancel(UUID runId) {
    return runs.cancel(runId, clock.instant());
  }
}
